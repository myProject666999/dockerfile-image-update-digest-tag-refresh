package com.salesforce.dockerfileimageupdate.digest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory fake OCI/Docker registry for tests. It serves manifest lists and single
 * manifests by tag, computes real sha256 digests for the documents it serves, records every
 * request it receives (so tests can assert how many resolutions actually hit the
 * "registry"), and can simulate tags being republished, missing tags/digests, timeouts and
 * the Bearer token auth flow.
 */
public class FakeRegistryServer {

    /**
     * One served manifest document: its body, its digest, and (for lists) platform digests.
     */
    public static class FakeManifest {
        private final boolean manifestList;
        private final String body;
        private final String digest;
        private final Map<String, String> platformDigests;

        FakeManifest(boolean manifestList, String body, String digest, Map<String, String> platformDigests) {
            this.manifestList = manifestList;
            this.body = body;
            this.digest = digest;
            this.platformDigests = platformDigests;
        }

        public boolean isManifestList() {
            return manifestList;
        }

        public String getDigest() {
            return digest;
        }

        public Map<String, String> getPlatformDigests() {
            return platformDigests;
        }
    }

    /**
     * A request the fake registry received.
     */
    public static class RecordedRequest {
        private final String path;
        private final String acceptHeader;
        private final String authorizationHeader;

        RecordedRequest(String path, String acceptHeader, String authorizationHeader) {
            this.path = path;
            this.acceptHeader = acceptHeader;
            this.authorizationHeader = authorizationHeader;
        }

        public String getPath() {
            return path;
        }

        public String getAcceptHeader() {
            return acceptHeader;
        }

        public String getAuthorizationHeader() {
            return authorizationHeader;
        }
    }

    private static final String TOKEN_PATH = "/token";
    private static final String TOKEN_VALUE = "fake-registry-token";

    private HttpServer server;
    // repo -> tag -> manifest
    private final Map<String, Map<String, FakeManifest>> repos = new ConcurrentHashMap<>();
    // digest -> manifest; content-addressable, entries survive tag republishes and removals
    private final Map<String, FakeManifest> manifestsByDigest = new ConcurrentHashMap<>();
    // repo:tag -> publish version, bumped on every (re)publish so digests change
    private final Map<String, AtomicInteger> publishVersions = new ConcurrentHashMap<>();
    private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Long> sleepMillisByTag = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> transientFailuresByTag = new ConcurrentHashMap<>();
    private volatile String requiredUser;
    private volatile String requiredPassword;

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * @return host:port authority of this fake registry, usable as the registry part of an
     * image name
     */
    public String getAuthority() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Publish a manifest list for {@code repo}:{@code tag} with one entry per platform
     * (e.g. {@code "linux/amd64"}). Publishing the same tag again simulates a republish:
     * all digests change.
     *
     * @return the published manifest, whose digests tests can embed in Dockerfiles
     */
    public FakeManifest publishManifestList(String repo, String tag, String... platforms) {
        int version = publishVersions.computeIfAbsent(repo + ":" + tag, k -> new AtomicInteger(0))
                .incrementAndGet();
        JSONArray manifests = new JSONArray();
        Map<String, String> platformDigests = new LinkedHashMap<>();
        for (String platform : platforms) {
            String[] parts = platform.split("/");
            String platformBody = singleManifestBody(repo, tag, platform, version);
            String platformDigest = sha256(platformBody);
            JSONObject platformJson = new JSONObject()
                    .put("os", parts[0])
                    .put("architecture", parts[1]);
            if (parts.length > 2) {
                platformJson.put("variant", parts[2]);
            }
            manifests.put(new JSONObject()
                    .put("mediaType", "application/vnd.docker.distribution.manifest.v2+json")
                    .put("digest", platformDigest)
                    .put("size", platformBody.length())
                    .put("platform", platformJson));
            platformDigests.put(platform, platformDigest);
        }
        String body = new JSONObject()
                .put("schemaVersion", 2)
                .put("mediaType", "application/vnd.docker.distribution.manifest.list.v2+json")
                .put("fakeVersion", version)
                .put("manifests", manifests)
                .toString();
        FakeManifest manifest = new FakeManifest(true, body, sha256(body), platformDigests);
        repos.computeIfAbsent(repo, k -> new ConcurrentHashMap<>()).put(tag, manifest);
        manifestsByDigest.put(manifest.getDigest(), manifest);
        return manifest;
    }

    /**
     * Publish a single (non-list) image manifest for {@code repo}:{@code tag}.
     */
    public FakeManifest publishSingleManifest(String repo, String tag) {
        int version = publishVersions.computeIfAbsent(repo + ":" + tag, k -> new AtomicInteger(0))
                .incrementAndGet();
        String body = singleManifestBody(repo, tag, "linux/amd64", version);
        FakeManifest manifest = new FakeManifest(false, body, sha256(body), Collections.emptyMap());
        repos.computeIfAbsent(repo, k -> new ConcurrentHashMap<>()).put(tag, manifest);
        manifestsByDigest.put(manifest.getDigest(), manifest);
        return manifest;
    }

    /**
     * Republish an existing tag so it points to new digests (simulates a security-patch
     * republish of the same tag).
     */
    public FakeManifest republish(String repo, String tag) {
        FakeManifest current = repos.getOrDefault(repo, Collections.emptyMap()).get(tag);
        if (current == null) {
            throw new IllegalArgumentException("No such tag to republish: " + repo + ":" + tag);
        }
        if (current.isManifestList()) {
            return publishManifestList(repo, tag, current.getPlatformDigests().keySet().toArray(new String[0]));
        }
        return publishSingleManifest(repo, tag);
    }

    /**
     * Remove a tag entirely (simulates a tag whose old digest can no longer be inspected).
     */
    public void removeTag(String repo, String tag) {
        Map<String, FakeManifest> repoManifests = repos.get(repo);
        if (repoManifests != null) {
            repoManifests.remove(tag);
        }
    }

    /**
     * Requests for this tag hang for the given time (simulates a resolution timeout).
     */
    public void setSleepOnTag(String tag, long millis) {
        sleepMillisByTag.put(tag, millis);
    }

    /**
     * Requests for this tag fail with a 500 the given number of times before succeeding
     * (simulates transient registry errors).
     */
    public void setTransientFailuresOnTag(String tag, int failures) {
        transientFailuresByTag.put(tag, new AtomicInteger(failures));
    }

    /**
     * Require the Bearer token flow: manifest requests get a 401 challenge; the token
     * endpoint only issues a token when given these basic credentials.
     */
    public void requireAuth(String user, String password) {
        this.requiredUser = user;
        this.requiredPassword = password;
    }

    public List<RecordedRequest> getRequests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    /**
     * @return number of manifest requests (not token requests) received for {@code repo}:{@code tag}
     */
    public int getManifestRequestCount(String repo, String tag) {
        String suffix = "/v2/" + repo + "/manifests/" + tag;
        int count = 0;
        for (RecordedRequest request : getRequests()) {
            if (request.getPath().equals(suffix)) {
                count++;
            }
        }
        return count;
    }

    public int getTotalRequestCount() {
        return getRequests().size();
    }

    private static String singleManifestBody(String repo, String tag, String platform, int version) {
        return new JSONObject()
                .put("schemaVersion", 2)
                .put("mediaType", "application/vnd.docker.distribution.manifest.v2+json")
                .put("fakeRepo", repo)
                .put("fakeTag", tag)
                .put("fakePlatform", platform)
                .put("fakeVersion", version)
                .toString();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(new RecordedRequest(
                path,
                exchange.getRequestHeaders().getFirst("Accept"),
                exchange.getRequestHeaders().getFirst("Authorization")));
        try {
            if (path.equals(TOKEN_PATH)) {
                handleToken(exchange);
                return;
            }
            if (requiredUser != null && !TOKEN_VALUE.equals(bearerToken(exchange))) {
                String challenge = "Bearer realm=\"http://" + getAuthority() + TOKEN_PATH
                        + "\",service=\"fake-registry\",scope=\"repository:fake:pull\"";
                exchange.getResponseHeaders().set("WWW-Authenticate", challenge);
                send(exchange, 401, "{\"errors\":[{\"code\":\"UNAUTHORIZED\"}]}");
                return;
            }
            if (path.startsWith("/v2/") && path.contains("/manifests/")) {
                handleManifest(exchange, path);
                return;
            }
            send(exchange, 404, "{\"errors\":[{\"code\":\"NOT_FOUND\"}]}");
        } finally {
            exchange.close();
        }
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        if (requiredUser != null) {
            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    (requiredUser + ":" + requiredPassword).getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, "{\"errors\":[{\"code\":\"UNAUTHORIZED\"}]}");
                return;
            }
        }
        send(exchange, 200, new JSONObject().put("token", TOKEN_VALUE).toString());
    }

    private void handleManifest(HttpExchange exchange, String path) throws IOException {
        String remainder = path.substring("/v2/".length());
        int manifestsIndex = remainder.lastIndexOf("/manifests/");
        String repo = remainder.substring(0, manifestsIndex);
        String tag = remainder.substring(manifestsIndex + "/manifests/".length());

        Long sleepMillis = sleepMillisByTag.get(tag);
        if (sleepMillis != null) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        AtomicInteger failures = transientFailuresByTag.get(tag);
        if (failures != null && failures.getAndDecrement() > 0) {
            send(exchange, 500, "{\"errors\":[{\"code\":\"UNKNOWN\"}]}");
            return;
        }
        // Tags cannot contain ':', so a reference with one is a digest lookup against the
        // content-addressable store.
        FakeManifest manifest = tag.contains(":")
                ? manifestsByDigest.get(tag)
                : repos.getOrDefault(repo, Collections.emptyMap()).get(tag);
        if (manifest == null) {
            send(exchange, 404, "{\"errors\":[{\"code\":\"MANIFEST_UNKNOWN\"}]}");
            return;
        }
        exchange.getResponseHeaders().set("Docker-Content-Digest", manifest.digest);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        send(exchange, 200, manifest.body);
    }

    private static String bearerToken(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length());
        }
        return null;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String sha256(String content) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hash = messageDigest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

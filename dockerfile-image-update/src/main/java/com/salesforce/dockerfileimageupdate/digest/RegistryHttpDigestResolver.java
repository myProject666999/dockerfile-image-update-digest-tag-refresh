package com.salesforce.dockerfileimageupdate.digest;

import com.salesforce.dockerfileimageupdate.model.ImageReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Resolves digests using the Docker Registry HTTP API v2.
 *
 * The request shape is pinned: every manifest fetch sends exactly {@link #ACCEPT_HEADER} as
 * its {@code Accept} header. Registries may return different manifest formats (and therefore
 * different digests) depending on the requested media types, so keeping this header constant
 * is what prevents the resolved digest from flip-flopping between runs.
 *
 * Authentication supports the standard Bearer token flow: on a 401 challenge the resolver
 * fetches a token from the challenge realm (using basic credentials from the
 * {@link RegistryCredentialsProvider} when configured for that host) and retries once.
 */
public class RegistryHttpDigestResolver implements DigestResolver {

    private static final Logger log = LoggerFactory.getLogger(RegistryHttpDigestResolver.class);

    /**
     * The single, pinned Accept header used for every manifest request. Order matters and
     * must not change: manifest lists/indexes are preferred so multi-arch tags resolve to
     * the list digest.
     */
    public static final String ACCEPT_HEADER = String.join(", ",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    private static final String DOCKER_CONTENT_DIGEST = "Docker-Content-Digest";
    private static final String BEARER_PREFIX = "Bearer ";

    private final OkHttpClient client;
    private final RegistryCredentialsProvider credentialsProvider;
    private final Map<String, String> tokenCache = new ConcurrentHashMap<>();

    public RegistryHttpDigestResolver(RegistryCredentialsProvider credentialsProvider) {
        this(new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build(),
                credentialsProvider);
    }

    public RegistryHttpDigestResolver(OkHttpClient client, RegistryCredentialsProvider credentialsProvider) {
        this.client = client;
        this.credentialsProvider = credentialsProvider;
    }

    @Override
    public TagResolution resolve(String imageName, String tag) throws DigestResolutionException {
        RegistryCoordinates coordinates = registryCoordinates(imageName);
        String manifestUrl = coordinates.apiUrl + "/v2/" + coordinates.repositoryPath + "/manifests/" + tag;
        Response response = executeWithAuth(manifestUrl, coordinates.credentialsHost, imageName, tag);
        try {
            handleErrorStatus(response, imageName, tag);
            ResponseBody body = response.body();
            if (body == null) {
                throw new DigestResolutionException(
                        String.format("Empty body resolving %s:%s.", imageName, tag), true);
            }
            final byte[] bodyBytes;
            try {
                bodyBytes = body.bytes();
            } catch (IOException e) {
                throw new DigestResolutionException(
                        String.format("I/O error reading manifest for %s:%s.", imageName, tag), e, true);
            }
            String digest = Optional.ofNullable(response.header(DOCKER_CONTENT_DIGEST))
                    .orElse(sha256Hex(bodyBytes));
            return parseManifest(digest, new String(bodyBytes, StandardCharsets.UTF_8), imageName, tag);
        } finally {
            response.close();
        }
    }

    @Override
    public Optional<DigestKind> identifyKind(String imageName, String digest) throws DigestResolutionException {
        RegistryCoordinates coordinates = registryCoordinates(imageName);
        String manifestUrl = coordinates.apiUrl + "/v2/" + coordinates.repositoryPath + "/manifests/" + digest;
        Response response = executeWithAuth(manifestUrl, coordinates.credentialsHost, imageName, digest);
        try {
            if (response.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                // The digest no longer exists in the registry.
                return Optional.empty();
            }
            handleErrorStatus(response, imageName, digest);
            ResponseBody body = response.body();
            if (body == null) {
                throw new DigestResolutionException(
                        String.format("Empty body fetching digest %s of %s.", digest, imageName), true);
            }
            final String content;
            try {
                content = body.string();
            } catch (IOException e) {
                throw new DigestResolutionException(
                        String.format("I/O error fetching digest %s of %s.", digest, imageName), e, true);
            }
            JSONObject doc;
            try {
                doc = new JSONObject(content);
            } catch (Exception e) {
                throw new DigestResolutionException(
                        String.format("Unparseable manifest for digest %s of %s.", digest, imageName), e, false);
            }
            return Optional.of(doc.has("manifests") ? DigestKind.manifestList() : DigestKind.singleManifest());
        } finally {
            response.close();
        }
    }

    private RegistryCoordinates registryCoordinates(String imageName) throws DigestResolutionException {
        ImageReference coordinates = new ImageReference(imageName, null, null);
        String credentialsHost = coordinates.getRegistryHost();
        String repositoryPath = coordinates.getRepositoryPath();
        if (credentialsHost == null || repositoryPath == null) {
            throw new DigestResolutionException("Cannot determine registry coordinates for " + imageName, false);
        }
        // Docker Hub's registry API lives on registry-1.docker.io; credentials are still
        // looked up by the host as written in the image reference ("docker.io").
        String apiHost = "docker.io".equals(credentialsHost) ? "registry-1.docker.io" : credentialsHost;
        return new RegistryCoordinates(schemeFor(apiHost) + "://" + apiHost, credentialsHost, repositoryPath);
    }

    private static final class RegistryCoordinates {
        private final String apiUrl;
        private final String credentialsHost;
        private final String repositoryPath;

        private RegistryCoordinates(String apiUrl, String credentialsHost, String repositoryPath) {
            this.apiUrl = apiUrl;
            this.credentialsHost = credentialsHost;
            this.repositoryPath = repositoryPath;
        }
    }

    /**
     * Localhost registries (including the in-memory test registry) speak plain HTTP;
     * everything else is HTTPS.
     */
    protected String schemeFor(String registryHost) {
        String hostOnly = registryHost.split(":")[0];
        if ("localhost".equals(hostOnly) || "127.0.0.1".equals(hostOnly) || "::1".equals(hostOnly)) {
            return "http";
        }
        return "https";
    }

    /**
     * Execute a GET against the registry, performing the Bearer token exchange and one
     * retry when the registry challenges with a 401.
     */
    private Response executeWithAuth(String manifestUrl, String credentialsHost,
                                     String imageName, String reference)
            throws DigestResolutionException {
        Response response = execute(newGet(manifestUrl, null), imageName, reference);
        if (response.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
            String challenge = response.header("WWW-Authenticate");
            response.close();
            String token = authenticate(challenge, credentialsHost, imageName, reference);
            response = execute(newGet(manifestUrl, token), imageName, reference);
        }
        return response;
    }

    private void handleErrorStatus(Response response, String imageName, String reference)
            throws DigestResolutionException {
        int code = response.code();
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            throw new DigestResolutionException(
                    String.format("Reference %s:%s not found in registry (404).", imageName, reference), false);
        }
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new DigestResolutionException(
                    String.format("Not authorized to resolve %s:%s (%d). Check credentials in %s.",
                            imageName, reference, code, RegistryCredentialsProvider.CREDENTIALS_ENV_VAR), false);
        }
        if (code == 429 || code >= 500) {
            throw new DigestResolutionException(
                    String.format("Registry returned %d resolving %s:%s.", code, imageName, reference), true);
        }
        if (code != HttpURLConnection.HTTP_OK) {
            throw new DigestResolutionException(
                    String.format("Unexpected status %d resolving %s:%s.", code, imageName, reference), false);
        }
    }

    private Response execute(Request request, String imageName, String tag) throws DigestResolutionException {
        try {
            return client.newCall(request).execute();
        } catch (IOException e) {
            throw new DigestResolutionException(
                    String.format("I/O error resolving %s:%s: %s", imageName, tag, e.getMessage()), e, true);
        }
    }

    private Request newGet(String url, String bearerToken) {
        Request.Builder builder = new Request.Builder().url(url).get()
                .header("Accept", ACCEPT_HEADER);
        if (bearerToken != null) {
            builder.header("Authorization", BEARER_PREFIX + bearerToken);
        }
        return builder.build();
    }

    /**
     * Perform the Bearer token exchange described by a 401 challenge. Tokens are cached per
     * registry/scope for the lifetime of this resolver (i.e. one run).
     */
    private String authenticate(String challenge, String registryHost,
                                String imageName, String reference) throws DigestResolutionException {
        if (challenge == null || !challenge.startsWith(BEARER_PREFIX)) {
            throw new DigestResolutionException(
                    String.format("Registry requires authentication for %s:%s but offered no Bearer challenge.",
                            imageName, reference), false);
        }
        Map<String, String> params = parseChallenge(challenge.substring(BEARER_PREFIX.length()));
        String realm = params.get("realm");
        if (realm == null) {
            throw new DigestResolutionException("Bearer challenge without realm for " + imageName, false);
        }
        String service = params.get("service");
        String scope = params.get("scope");
        String cacheKey = registryHost + "|" + service + "|" + scope;
        String cached = tokenCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        StringBuilder tokenUrl = new StringBuilder(realm);
        tokenUrl.append(realm.contains("?") ? "&" : "?");
        if (service != null) {
            tokenUrl.append("service=").append(URLEncoder.encode(service, StandardCharsets.UTF_8));
        }
        if (scope != null) {
            tokenUrl.append("&scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
        }
        Request.Builder tokenRequest = new Request.Builder().url(tokenUrl.toString()).get();
        Optional<RegistryCredentialsProvider.RegistryCredential> credential =
                credentialsProvider.credentialsFor(registryHost);
        if (credential.isPresent()) {
            String basic = credential.get().getUsername() + ":" + credential.get().getPassword();
            tokenRequest.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
        }
        Response response = execute(tokenRequest.build(), imageName, reference);
        try {
            if (response.code() != HttpURLConnection.HTTP_OK) {
                throw new DigestResolutionException(
                        String.format("Could not obtain registry token for %s:%s (status %d).",
                                imageName, reference, response.code()), false);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new DigestResolutionException("Empty token response for " + imageName, false);
            }
            final JSONObject tokenJson;
            try {
                tokenJson = new JSONObject(body.string());
            } catch (IOException e) {
                throw new DigestResolutionException("I/O error reading token response for " + imageName, e, true);
            }
            String token = tokenJson.optString("token", tokenJson.optString("access_token", null));
            if (token == null || token.isEmpty()) {
                throw new DigestResolutionException("Token response without token for " + imageName, false);
            }
            tokenCache.put(cacheKey, token);
            return token;
        } finally {
            response.close();
        }
    }

    private static Map<String, String> parseChallenge(String challengeParams) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : challengeParams.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            params.put(key, value);
        }
        return params;
    }

    /**
     * Parse a manifest or manifest list document. A document with a {@code manifests} array
     * is a list/index: its own digest is the top digest and each entry contributes a
     * platform digest. Anything else is treated as a single image manifest.
     */
    protected TagResolution parseManifest(String topDigest, String body, String imageName, String tag)
            throws DigestResolutionException {
        JSONObject doc;
        try {
            doc = new JSONObject(body);
        } catch (Exception e) {
            throw new DigestResolutionException(
                    String.format("Unparseable manifest for %s:%s.", imageName, tag), e, false);
        }
        Map<String, String> platformDigests = new LinkedHashMap<>();
        if (doc.has("manifests")) {
            JSONArray manifests = doc.getJSONArray("manifests");
            for (int i = 0; i < manifests.length(); i++) {
                JSONObject entry = manifests.getJSONObject(i);
                String entryDigest = entry.optString("digest", null);
                JSONObject platform = entry.optJSONObject("platform");
                if (entryDigest == null || platform == null) {
                    continue;
                }
                String os = platform.optString("os", "");
                String architecture = platform.optString("architecture", "");
                if (os.isEmpty() || architecture.isEmpty()) {
                    continue;
                }
                String platformKey = os + "/" + architecture;
                String variant = platform.optString("variant", "");
                if (!variant.isEmpty()) {
                    platformKey += "/" + variant;
                }
                platformDigests.put(platformKey, entryDigest);
            }
            return new TagResolution(topDigest, true, platformDigests);
        }
        return new TagResolution(topDigest, false, platformDigests);
    }

    private static String sha256Hex(byte[] content) throws DigestResolutionException {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hash = messageDigest.digest(content);
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new DigestResolutionException("SHA-256 not available.", e, false);
        }
    }
}

package com.salesforce.dockerfileimageupdate.utils;

import com.salesforce.dockerfileimageupdate.digest.CachingDigestResolver;
import com.salesforce.dockerfileimageupdate.digest.DigestUpdateContext;
import com.salesforce.dockerfileimageupdate.digest.FakeRegistryServer;
import com.salesforce.dockerfileimageupdate.digest.RegistryCredentialsProvider;
import com.salesforce.dockerfileimageupdate.digest.RegistryHttpDigestResolver;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * End-to-end tests for digest-aware rewriting of Dockerfiles / docker-compose files against
 * an in-memory fake registry. Every scenario verifies the complete rewritten line text and
 * the number of requests the fake registry actually received.
 */
public class DigestAwareRewriteTest {

    private static final String REPO = "team/img";

    private FakeRegistryServer registry;
    private String image;
    private CachingDigestResolver cachingResolver;
    private DigestUpdateContext context;
    private DockerfileGitHubUtil dockerfileGitHubUtil;

    @BeforeMethod
    public void setUp() throws IOException {
        registry = new FakeRegistryServer();
        registry.start();
        image = registry.getAuthority() + "/" + REPO;
        cachingResolver = new CachingDigestResolver(
                new RegistryHttpDigestResolver(RegistryCredentialsProvider.empty()));
        context = new DigestUpdateContext(cachingResolver, true);
        dockerfileGitHubUtil = new DockerfileGitHubUtil(null);
        dockerfileGitHubUtil.setDigestUpdateContext(context);
    }

    @AfterMethod
    public void tearDown() {
        registry.stop();
    }

    private String rewrite(String content, String targetTag) throws IOException {
        StringBuilder out = new StringBuilder();
        dockerfileGitHubUtil.rewriteDockerfile("img", targetTag,
                new BufferedReader(new StringReader(content)), out, "");
        return out.toString();
    }

    @Test
    public void testTagAndManifestListDigestUpdatedTogether() throws Exception {
        FakeRegistryServer.FakeManifest old =
                registry.publishManifestList(REPO, "1.0", "linux/amd64", "linux/arm64");
        FakeRegistryServer.FakeManifest current =
                registry.publishManifestList(REPO, "2.0", "linux/amd64", "linux/arm64");

        String result = rewrite("FROM " + image + ":1.0@" + old.getDigest() + "\n", "2.0");

        assertEquals(result, "FROM " + image + ":2.0@" + current.getDigest() + "\n");
        // One resolution for the old tag (to identify the digest kind), one for the new tag.
        assertEquals(registry.getManifestRequestCount(REPO, "1.0"), 1);
        assertEquals(registry.getManifestRequestCount(REPO, "2.0"), 1);
        assertEquals(registry.getTotalRequestCount(), 2);
    }

    @Test
    public void testPlatformDigestKindPreservedSeparatelyFromListKind() throws Exception {
        FakeRegistryServer.FakeManifest old =
                registry.publishManifestList(REPO, "1.0", "linux/amd64", "linux/arm64");
        FakeRegistryServer.FakeManifest current =
                registry.publishManifestList(REPO, "2.0", "linux/amd64", "linux/arm64");
        String oldAmd64 = old.getPlatformDigests().get("linux/amd64");
        String newAmd64 = current.getPlatformDigests().get("linux/amd64");

        // One "file" pins the manifest list digest, another pins a single platform digest.
        // Both are updated in the same run, each keeping its own digest kind.
        String listFile = rewrite("FROM " + image + ":1.0@" + old.getDigest() + "\n", "2.0");
        String platformFile = rewrite("FROM " + image + ":1.0@" + oldAmd64 + "\n", "2.0");

        assertEquals(listFile, "FROM " + image + ":2.0@" + current.getDigest() + "\n");
        assertEquals(platformFile, "FROM " + image + ":2.0@" + newAmd64 + "\n");
        // The two files share the same cached resolutions: still exactly one request per tag.
        assertEquals(registry.getManifestRequestCount(REPO, "1.0"), 1);
        assertEquals(registry.getManifestRequestCount(REPO, "2.0"), 1);
        assertEquals(registry.getTotalRequestCount(), 2);
    }

    @Test
    public void testOldDigestNoLongerExistsKeepsLineUnchanged() throws Exception {
        registry.publishManifestList(REPO, "1.0", "linux/amd64");
        registry.publishManifestList(REPO, "2.0", "linux/amd64");
        String vanishedDigest = "sha256:" + new String(new char[64]).replace('\0', '0');
        String line = "FROM " + image + ":1.0@" + vanishedDigest;

        String result = rewrite(line + "\n", "2.0");

        // The old digest cannot be found in the registry, so its kind is unknown: the most
        // conservative action is to leave the whole reference as it is and report it.
        assertEquals(result, line + "\n");
        assertEquals(registry.getManifestRequestCount(REPO, "1.0"), 1);
        assertEquals(registry.getManifestRequestCount(REPO, "2.0"), 0);
        assertTrue(context.getReport().stream().anyMatch(entry -> entry.contains("no longer exists")),
                "Expected a report entry about the vanished digest, got: " + context.getReport());
    }

    @Test
    public void testDigestOnlyReferenceIsLeftAlone() throws Exception {
        String line = "FROM " + image + "@sha256:"
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        String result = rewrite(line + "\n", "2.0");

        assertEquals(result, line + "\n");
        assertEquals(registry.getTotalRequestCount(), 0);
        assertTrue(context.getReport().stream().anyMatch(entry -> entry.contains("without a tag")),
                "Expected a report entry about the digest-only reference, got: " + context.getReport());
    }

    @Test
    public void testSameTagRepublishedDigestIsRefreshed() throws Exception {
        FakeRegistryServer.FakeManifest original =
                registry.publishManifestList(REPO, "2.0", "linux/amd64");
        String line = "FROM " + image + ":2.0@" + original.getDigest();
        // The tag is republished (e.g. a security patch) before the tool runs.
        FakeRegistryServer.FakeManifest republished = registry.republish(REPO, "2.0");

        String result = rewrite(line + "\n", "2.0");

        assertEquals(result, "FROM " + image + ":2.0@" + republished.getDigest() + "\n");
        // One resolution of the tag plus one content-addressable fetch of the old digest to
        // identify its kind (the old digest is no longer part of the tag's resolution).
        assertEquals(registry.getManifestRequestCount(REPO, "2.0"), 1);
        assertEquals(registry.getManifestRequestCount(REPO, original.getDigest()), 1);
        assertEquals(registry.getTotalRequestCount(), 2);
        assertTrue(context.getReport().stream().anyMatch(entry -> entry.contains("republished")),
                "Expected a report entry about the republished tag, got: " + context.getReport());
    }

    @Test
    public void testSameTagDigestRefreshCanBeDisabled() throws Exception {
        FakeRegistryServer.FakeManifest original =
                registry.publishManifestList(REPO, "2.0", "linux/amd64");
        String line = "FROM " + image + ":2.0@" + original.getDigest();
        registry.republish(REPO, "2.0");
        dockerfileGitHubUtil.setDigestUpdateContext(
                new DigestUpdateContext(cachingResolver, false));

        String result = rewrite(line + "\n", "2.0");

        assertEquals(result, line + "\n");
        assertEquals(registry.getTotalRequestCount(), 0);
    }

    @Test
    public void testSameTagUpToDateDigestIsUntouched() throws Exception {
        FakeRegistryServer.FakeManifest current =
                registry.publishManifestList(REPO, "2.0", "linux/amd64");
        String line = "FROM " + image + ":2.0@" + current.getDigest();

        String result = rewrite(line + "\n", "2.0");

        assertEquals(result, line + "\n");
        assertEquals(registry.getManifestRequestCount(REPO, "2.0"), 1);
    }

    @Test
    public void testResolutionFailureKeepsWholeLineUnchanged() throws Exception {
        FakeRegistryServer.FakeManifest old =
                registry.publishManifestList(REPO, "1.0", "linux/amd64");
        String line = "FROM " + image + ":1.0@" + old.getDigest();

        // The target tag does not exist in the registry.
        String result = rewrite(line + "\n", "does-not-exist");

        // The tag must not be updated on its own: the whole reference stays as it was.
        assertEquals(result, line + "\n");
        assertEquals(registry.getManifestRequestCount(REPO, "1.0"), 1);
        assertEquals(registry.getManifestRequestCount(REPO, "does-not-exist"), 1);
        assertTrue(context.getReport().stream()
                        .anyMatch(entry -> entry.contains("could not resolve digest for new tag")),
                "Expected a report entry about the failed resolution, got: " + context.getReport());
    }

    @Test
    public void testTimeoutKeepsWholeLineUnchanged() throws Exception {
        FakeRegistryServer.FakeManifest old =
                registry.publishManifestList(REPO, "1.0", "linux/amd64");
        registry.publishManifestList(REPO, "2.0", "linux/amd64");
        registry.setSleepOnTag("2.0", 5000);
        // A resolver with a short read timeout and a single attempt: one flaky timeout must
        // not poison anything; the line simply stays unchanged.
        CachingDigestResolver shortTimeoutResolver = new CachingDigestResolver(
                new RegistryHttpDigestResolver(
                        new okhttp3.OkHttpClient.Builder()
                                .connectTimeout(1, TimeUnit.SECONDS)
                                .readTimeout(500, TimeUnit.MILLISECONDS)
                                .build(),
                        RegistryCredentialsProvider.empty()),
                1);
        dockerfileGitHubUtil.setDigestUpdateContext(
                new DigestUpdateContext(shortTimeoutResolver, true));
        String line = "FROM " + image + ":1.0@" + old.getDigest();

        String result = rewrite(line + "\n", "2.0");

        assertEquals(result, line + "\n");
        assertTrue(context.getReport().isEmpty(), "Report lives on the original context");
    }

    @Test
    public void testTwoFilesWithSameImageAndTagShareOneResolution() throws Exception {
        FakeRegistryServer.FakeManifest old =
                registry.publishManifestList(REPO, "1.0", "linux/amd64");
        FakeRegistryServer.FakeManifest current =
                registry.publishManifestList(REPO, "2.0", "linux/amd64");

        String fileOne = rewrite("FROM " + image + ":1.0@" + old.getDigest() + "\n", "2.0");
        String fileTwo = rewrite("FROM " + image + ":1.0@" + old.getDigest() + "\n", "2.0");

        assertEquals(fileOne, "FROM " + image + ":2.0@" + current.getDigest() + "\n");
        assertEquals(fileTwo, "FROM " + image + ":2.0@" + current.getDigest() + "\n");
        // Dozens of files referencing the same image:tag must not each hit the registry.
        assertEquals(registry.getManifestRequestCount(REPO, "1.0"), 1);
        assertEquals(registry.getManifestRequestCount(REPO, "2.0"), 1);
        assertEquals(registry.getTotalRequestCount(), 2);
    }

    @Test
    public void testConcurrentResolutionHitsRegistryOnce() throws Exception {
        registry.publishManifestList(REPO, "1.0", "linux/amd64");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    cachingResolver.resolve(image, "1.0");
                    successes.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(failures.get(), 0);
        assertEquals(successes.get(), threads);
        assertEquals(registry.getManifestRequestCount(REPO, "1.0"), 1,
                "Concurrent resolutions of the same key must merge into exactly one request");
    }

    @Test
    public void testArgDefaultValueUpdatedAndVariableReferenceUntouched() throws Exception {
        FakeRegistryServer.FakeManifest old =
                registry.publishManifestList(REPO, "1.0", "linux/amd64");
        FakeRegistryServer.FakeManifest current =
                registry.publishManifestList(REPO, "2.0", "linux/amd64");
        String content = "ARG BASE_IMAGE=" + image + ":1.0@" + old.getDigest() + "\n"
                + "FROM ${BASE_IMAGE}\n"
                + "RUN echo hello\n";

        String result = rewrite(content, "2.0");

        assertEquals(result, "ARG BASE_IMAGE=" + image + ":2.0@" + current.getDigest() + "\n"
                + "FROM ${BASE_IMAGE}\n"
                + "RUN echo hello\n");
        assertEquals(registry.getTotalRequestCount(), 2);
    }

    @Test
    public void testCommentAboveUpdatedLineIsLeftAloneButReported() throws Exception {
        FakeRegistryServer.FakeManifest old =
                registry.publishManifestList(REPO, "1.0", "linux/amd64");
        FakeRegistryServer.FakeManifest current =
                registry.publishManifestList(REPO, "2.0", "linux/amd64");
        String comment = "# pinned base image " + image + ":1.0, check before upgrading";
        String content = comment + "\n"
                + "FROM " + image + ":1.0@" + old.getDigest() + "\n";

        String result = rewrite(content, "2.0");

        assertEquals(result, comment + "\n"
                + "FROM " + image + ":2.0@" + current.getDigest() + "\n");
        assertTrue(context.getReport().stream()
                        .anyMatch(entry -> entry.contains("comment") && entry.contains("1.0")),
                "Expected a report entry about the stale comment, got: " + context.getReport());
    }

    @Test
    public void testComposeImageLineUpdatedAtomically() throws Exception {
        FakeRegistryServer.FakeManifest old =
                registry.publishManifestList(REPO, "1.0", "linux/amd64");
        FakeRegistryServer.FakeManifest current =
                registry.publishManifestList(REPO, "2.0", "linux/amd64");
        String content = "services:\n"
                + "  app:\n"
                + "    image: " + image + ":1.0@" + old.getDigest() + "\n";

        String result = rewrite(content, "2.0");

        assertEquals(result, "services:\n"
                + "  app:\n"
                + "    image: " + image + ":2.0@" + current.getDigest() + "\n");
        assertEquals(registry.getTotalRequestCount(), 2);
    }

    @Test
    public void testComposeDigestOnlyLineIsLeftAlone() throws Exception {
        String line = "image: " + image + "@sha256:"
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        String result = rewrite(line + "\n", "2.0");

        assertEquals(result, line + "\n");
        assertEquals(registry.getTotalRequestCount(), 0);
    }

    @Test
    public void testPlainTagReferenceStillUpdatedWithoutRegistryCalls() throws Exception {
        String result = rewrite("FROM " + image + ":1.0\n", "2.0");

        assertEquals(result, "FROM " + image + ":2.0\n");
        assertEquals(registry.getTotalRequestCount(), 0);
    }

    @Test
    public void testPlatformMissingInNewTagKeepsLineUnchanged() throws Exception {
        FakeRegistryServer.FakeManifest old =
                registry.publishManifestList(REPO, "1.0", "linux/amd64", "linux/arm64");
        // The new tag dropped the arm64 platform.
        registry.publishManifestList(REPO, "2.0", "linux/amd64");
        String oldArm64 = old.getPlatformDigests().get("linux/arm64");
        String line = "FROM " + image + ":1.0@" + oldArm64;

        String result = rewrite(line + "\n", "2.0");

        assertEquals(result, line + "\n");
        assertTrue(context.getReport().stream()
                        .anyMatch(entry -> entry.contains("does not provide a digest of kind")),
                "Expected a report entry about the missing platform, got: " + context.getReport());
    }

    @Test
    public void testReportIsEmptyWhenNothingNoteworthyHappens() throws Exception {
        registry.publishManifestList(REPO, "1.0", "linux/amd64");
        registry.publishManifestList(REPO, "2.0", "linux/amd64");
        rewrite("FROM " + image + ":1.0\n", "2.0");

        List<String> report = context.getReport();
        assertTrue(report.isEmpty(), "Plain tag updates should not produce report entries");
    }
}

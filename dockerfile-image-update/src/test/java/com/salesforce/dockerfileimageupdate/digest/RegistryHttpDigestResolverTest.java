package com.salesforce.dockerfileimageupdate.digest;

import okhttp3.OkHttpClient;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class RegistryHttpDigestResolverTest {

    private FakeRegistryServer registry;
    private RegistryHttpDigestResolver resolver;

    @BeforeMethod
    public void setUp() throws IOException {
        registry = new FakeRegistryServer();
        registry.start();
        resolver = new RegistryHttpDigestResolver(RegistryCredentialsProvider.empty());
    }

    @AfterMethod
    public void tearDown() {
        registry.stop();
    }

    private String image(String repo) {
        return registry.getAuthority() + "/" + repo;
    }

    @Test
    public void testResolvesManifestListWithPlatformDigests() throws Exception {
        FakeRegistryServer.FakeManifest published =
                registry.publishManifestList("team/img", "1.0", "linux/amd64", "linux/arm64");

        TagResolution resolution = resolver.resolve(image("team/img"), "1.0");

        assertTrue(resolution.isManifestList());
        assertEquals(resolution.getTopDigest(), published.getDigest());
        assertEquals(resolution.getPlatformDigests(), published.getPlatformDigests());
    }

    @Test
    public void testResolvesSingleManifest() throws Exception {
        FakeRegistryServer.FakeManifest published = registry.publishSingleManifest("team/img", "1.0");

        TagResolution resolution = resolver.resolve(image("team/img"), "1.0");

        assertFalse(resolution.isManifestList());
        assertEquals(resolution.getTopDigest(), published.getDigest());
        assertTrue(resolution.getPlatformDigests().isEmpty());
    }

    @Test
    public void testAcceptHeaderIsPinned() throws Exception {
        registry.publishManifestList("team/img", "1.0", "linux/amd64");

        resolver.resolve(image("team/img"), "1.0");

        // The request shape must stay constant: the same tag fetched with different Accept
        // headers can return different manifest formats and therefore different digests.
        for (FakeRegistryServer.RecordedRequest request : registry.getRequests()) {
            assertEquals(request.getAcceptHeader(), RegistryHttpDigestResolver.ACCEPT_HEADER);
        }
    }

    @Test
    public void testRepublishedTagResolvesToNewDigest() throws Exception {
        FakeRegistryServer.FakeManifest original =
                registry.publishManifestList("team/img", "1.0", "linux/amd64");
        assertEquals(resolver.resolve(image("team/img"), "1.0").getTopDigest(), original.getDigest());

        FakeRegistryServer.FakeManifest republished = registry.republish("team/img", "1.0");

        assertEquals(resolver.resolve(image("team/img"), "1.0").getTopDigest(), republished.getDigest());
    }

    @Test
    public void testIdentifyKindOfManifestListDigest() throws Exception {
        FakeRegistryServer.FakeManifest published =
                registry.publishManifestList("team/img", "1.0", "linux/amd64");

        assertEquals(resolver.identifyKind(image("team/img"), published.getDigest()).orElse(null),
                DigestKind.manifestList());
    }

    @Test
    public void testIdentifyKindOfSingleManifestDigest() throws Exception {
        FakeRegistryServer.FakeManifest published = registry.publishSingleManifest("team/img", "1.0");

        assertEquals(resolver.identifyKind(image("team/img"), published.getDigest()).orElse(null),
                DigestKind.singleManifest());
    }

    @Test
    public void testIdentifyKindOfVanishedDigestIsEmpty() throws Exception {
        registry.publishManifestList("team/img", "1.0", "linux/amd64");
        String unknownDigest = "sha256:" + new String(new char[64]).replace('\0', 'f');

        assertFalse(resolver.identifyKind(image("team/img"), unknownDigest).isPresent());
    }

    @Test
    public void testIdentifyKindSurvivesRepublish() throws Exception {
        // Manifests are content-addressable: a digest can still be inspected after the tag
        // that pointed to it was republished.
        FakeRegistryServer.FakeManifest original =
                registry.publishManifestList("team/img", "1.0", "linux/amd64");
        registry.republish("team/img", "1.0");

        assertEquals(resolver.identifyKind(image("team/img"), original.getDigest()).orElse(null),
                DigestKind.manifestList());
    }

    @Test
    public void testMissingTagIsNonTransientFailure() {
        try {
            resolver.resolve(image("team/img"), "does-not-exist");
            fail("Expected DigestResolutionException");
        } catch (DigestResolutionException e) {
            assertFalse(e.isTransientFailure());
        }
    }

    @Test
    public void testTimeoutIsTransientFailure() {
        registry.publishManifestList("team/img", "slow", "linux/amd64");
        registry.setSleepOnTag("slow", 5000);
        RegistryHttpDigestResolver impatientResolver = new RegistryHttpDigestResolver(
                new OkHttpClient.Builder()
                        .connectTimeout(1, TimeUnit.SECONDS)
                        .readTimeout(500, TimeUnit.MILLISECONDS)
                        .build(),
                RegistryCredentialsProvider.empty());
        try {
            impatientResolver.resolve(image("team/img"), "slow");
            fail("Expected DigestResolutionException");
        } catch (DigestResolutionException e) {
            assertTrue(e.isTransientFailure());
        }
    }

    @Test
    public void testBearerTokenFlowWithCredentials() throws Exception {
        registry.requireAuth("robot", "s3cret");
        FakeRegistryServer.FakeManifest published =
                registry.publishManifestList("private/img", "2.0", "linux/amd64");
        Map<String, RegistryCredentialsProvider.RegistryCredential> credentials =
                Collections.singletonMap(registry.getAuthority(),
                        new RegistryCredentialsProvider.RegistryCredential("robot", "s3cret"));
        RegistryHttpDigestResolver authenticatedResolver =
                new RegistryHttpDigestResolver(new RegistryCredentialsProvider(credentials));

        TagResolution resolution = authenticatedResolver.resolve(image("private/img"), "2.0");

        assertEquals(resolution.getTopDigest(), published.getDigest());
        // The token endpoint must have been hit exactly once (token is cached for the run).
        authenticatedResolver.resolve(image("private/img"), "2.0");
        long tokenRequests = registry.getRequests().stream()
                .filter(request -> request.getPath().equals("/token"))
                .count();
        assertEquals(tokenRequests, 1);
    }

    @Test
    public void testAuthFailureIsNonTransient() {
        registry.requireAuth("robot", "s3cret");
        registry.publishManifestList("private/img", "2.0", "linux/amd64");
        // No credentials configured: the token endpoint rejects the anonymous request.
        try {
            resolver.resolve(image("private/img"), "2.0");
            fail("Expected DigestResolutionException");
        } catch (DigestResolutionException e) {
            assertFalse(e.isTransientFailure());
        }
    }

    @Test
    public void testCredentialsParsedFromEnvironment() {
        RegistryCredentialsProvider provider = RegistryCredentialsProvider.fromEnvironment(
                key -> RegistryCredentialsProvider.CREDENTIALS_ENV_VAR.equals(key)
                        ? "my.registry.com=user:pass" : null);
        assertTrue(provider.credentialsFor("my.registry.com").isPresent());
        assertEquals(provider.credentialsFor("my.registry.com").get().getUsername(), "user");
        assertEquals(provider.credentialsFor("my.registry.com").get().getPassword(), "pass");
        assertFalse(provider.credentialsFor("other.registry.com").isPresent());
    }
}

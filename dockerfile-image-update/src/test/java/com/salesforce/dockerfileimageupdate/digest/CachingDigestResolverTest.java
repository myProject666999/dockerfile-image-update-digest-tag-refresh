package com.salesforce.dockerfileimageupdate.digest;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class CachingDigestResolverTest {

    /**
     * A scripted delegate: counts calls per key and can be told to fail (transiently or
     * permanently) a number of times before succeeding.
     */
    private static class ScriptedResolver implements DigestResolver {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger transientFailuresRemaining;
        private final boolean permanentFailure;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean block;

        ScriptedResolver(int transientFailures, boolean permanentFailure, boolean block) {
            this.transientFailuresRemaining = new AtomicInteger(transientFailures);
            this.permanentFailure = permanentFailure;
            this.block = block;
        }

        @Override
        public TagResolution resolve(String imageName, String tag) throws DigestResolutionException {
            calls.incrementAndGet();
            entered.countDown();
            if (block) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (permanentFailure) {
                throw new DigestResolutionException("permanent", false);
            }
            if (transientFailuresRemaining.getAndDecrement() > 0) {
                throw new DigestResolutionException("transient", true);
            }
            return new TagResolution("sha256:resolved-" + tag, true, Collections.emptyMap());
        }

        @Override
        public Optional<DigestKind> identifyKind(String imageName, String digest) {
            return Optional.of(DigestKind.manifestList());
        }
    }

    @Test
    public void testSuccessfulResolutionIsCachedForTheRun() throws Exception {
        ScriptedResolver delegate = new ScriptedResolver(0, false, false);
        CachingDigestResolver caching = new CachingDigestResolver(delegate);

        TagResolution first = caching.resolve("img", "1.0");
        TagResolution second = caching.resolve("img", "1.0");

        assertEquals(delegate.calls.get(), 1);
        assertEquals(first.getTopDigest(), second.getTopDigest());
    }

    @Test
    public void testDifferentKeysResolveSeparately() throws Exception {
        ScriptedResolver delegate = new ScriptedResolver(0, false, false);
        CachingDigestResolver caching = new CachingDigestResolver(delegate);

        caching.resolve("img", "1.0");
        caching.resolve("img", "2.0");
        caching.resolve("other-img", "1.0");

        assertEquals(delegate.calls.get(), 3);
    }

    @Test
    public void testConcurrentResolutionsOfSameKeyMergeIntoOneRequest() throws Exception {
        ScriptedResolver delegate = new ScriptedResolver(0, false, true);
        CachingDigestResolver caching = new CachingDigestResolver(delegate);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TagResolution>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return caching.resolve("img", "1.0");
            }));
        }
        start.countDown();
        // Wait until one thread is inside the delegate, hold it, let the others pile up.
        assertTrue(delegate.entered.await(10, TimeUnit.SECONDS));
        delegate.release.countDown();

        List<TagResolution> results = new ArrayList<>();
        for (Future<TagResolution> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertEquals(delegate.calls.get(), 1,
                "Concurrent resolutions of the same key must merge into a single registry request");
        for (TagResolution result : results) {
            assertEquals(result.getTopDigest(), "sha256:resolved-1.0");
        }
    }

    @Test
    public void testTransientFailuresAreRetriedWithinBounds() throws Exception {
        ScriptedResolver delegate = new ScriptedResolver(2, false, false);
        CachingDigestResolver caching = new CachingDigestResolver(delegate, 3);

        TagResolution resolution = caching.resolve("img", "1.0");

        assertEquals(resolution.getTopDigest(), "sha256:resolved-1.0");
        assertEquals(delegate.calls.get(), 3);
        // Success is cached afterwards.
        caching.resolve("img", "1.0");
        assertEquals(delegate.calls.get(), 3);
    }

    @Test
    public void testRetriesAreBoundedForBrokenTag() throws Exception {
        ScriptedResolver delegate = new ScriptedResolver(Integer.MAX_VALUE, false, false);
        CachingDigestResolver caching = new CachingDigestResolver(delegate, 3);

        try {
            caching.resolve("img", "broken");
            fail("Expected DigestResolutionException");
        } catch (DigestResolutionException e) {
            assertTrue(e.isTransientFailure());
        }
        assertEquals(delegate.calls.get(), 3,
                "A broken tag must not be retried forever within one resolution");
    }

    @Test
    public void testPermanentFailuresAreNotRetried() throws Exception {
        ScriptedResolver delegate = new ScriptedResolver(0, true, false);
        CachingDigestResolver caching = new CachingDigestResolver(delegate, 3);

        try {
            caching.resolve("img", "missing");
            fail("Expected DigestResolutionException");
        } catch (DigestResolutionException e) {
            // expected
        }
        assertEquals(delegate.calls.get(), 1);
    }

    @Test
    public void testFailuresAreNotCached() throws Exception {
        // Fails transiently more often than one resolution's retry budget, so the first
        // resolve fails; a later resolve must try the registry again (and succeed).
        ScriptedResolver delegate = new ScriptedResolver(3, false, false);
        CachingDigestResolver caching = new CachingDigestResolver(delegate, 2);

        try {
            caching.resolve("img", "flaky");
            fail("Expected DigestResolutionException");
        } catch (DigestResolutionException e) {
            // expected: 2 attempts used up, 1 transient failure remains
        }
        assertEquals(delegate.calls.get(), 2);

        TagResolution resolution = caching.resolve("img", "flaky");
        assertEquals(resolution.getTopDigest(), "sha256:resolved-flaky");
        assertEquals(delegate.calls.get(), 4);
    }

    @Test
    public void testConcurrentFailurePropagatesToAllWaitersAndEvicts() throws Exception {
        ScriptedResolver delegate = new ScriptedResolver(0, true, true);
        CachingDigestResolver caching = new CachingDigestResolver(delegate, 1);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    caching.resolve("img", "gone");
                    return false;
                } catch (DigestResolutionException e) {
                    return true;
                }
            }));
        }
        // Every thread is about to call resolve; give them a moment to pile onto the
        // in-flight future before the owner's attempt fails.
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(delegate.entered.await(10, TimeUnit.SECONDS));
        Thread.sleep(200);
        delegate.release.countDown();

        for (Future<Boolean> future : futures) {
            assertTrue(future.get(10, TimeUnit.SECONDS), "All waiters must see the failure");
        }
        pool.shutdownNow();
        assertEquals(delegate.calls.get(), 1);
        // The failed key was evicted: a subsequent resolve hits the delegate again.
        try {
            caching.resolve("img", "gone");
            fail("Expected DigestResolutionException");
        } catch (DigestResolutionException e) {
            // expected
        }
        assertEquals(delegate.calls.get(), 2);
    }
}

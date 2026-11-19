package com.salesforce.dockerfileimageupdate.digest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * A {@link DigestResolver} decorator that caches resolutions in memory for the duration of
 * one run, keyed by (image, reference).
 *
 * Semantics:
 * <ul>
 *     <li>The same key is resolved at most once per run: concurrent callers share a single
 *     in-flight resolution (single-flight), and later callers reuse the first successful
 *     result. Even if a tag is republished mid-run, every reference in this run is updated
 *     from the same resolution, keeping the run internally consistent.</li>
 *     <li>Failures are <b>not</b> cached: a flaky timeout must not make every subsequent
 *     repository give up on the tag. The failed key is evicted so the next caller tries
 *     again.</li>
 *     <li>Retries of transient failures are bounded per resolution attempt
 *     ({@link #DEFAULT_MAX_ATTEMPTS}) so one broken tag cannot stall the whole run.</li>
 *     <li>The cache lives only in memory; nothing is written to disk or reused across runs,
 *     so a later run never sees a stale digest.</li>
 * </ul>
 */
public class CachingDigestResolver implements DigestResolver {

    private static final Logger log = LoggerFactory.getLogger(CachingDigestResolver.class);

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final DigestResolver delegate;
    private final int maxAttempts;
    private final ConcurrentHashMap<String, CompletableFuture<TagResolution>> resolutions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Optional<DigestKind>>> kindIdentifications =
            new ConcurrentHashMap<>();

    public CachingDigestResolver(DigestResolver delegate) {
        this(delegate, DEFAULT_MAX_ATTEMPTS);
    }

    public CachingDigestResolver(DigestResolver delegate, int maxAttempts) {
        this.delegate = delegate;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public TagResolution resolve(String imageName, String tag) throws DigestResolutionException {
        return singleFlight(resolutions, "tag:" + imageName + ":" + tag,
                () -> delegate.resolve(imageName, tag));
    }

    @Override
    public Optional<DigestKind> identifyKind(String imageName, String digest) throws DigestResolutionException {
        return singleFlight(kindIdentifications, "digest:" + imageName + ":" + digest,
                () -> delegate.identifyKind(imageName, digest));
    }

    /**
     * Run {@code task} for {@code key} exactly once per successful result: concurrent
     * callers wait on the same in-flight computation, successes stay cached for the run,
     * failures are evicted so the next caller retries.
     */
    private <T> T singleFlight(ConcurrentHashMap<String, CompletableFuture<T>> cache,
                               String key, Fallible<T> task) throws DigestResolutionException {
        CompletableFuture<T> fresh = new CompletableFuture<>();
        CompletableFuture<T> existing = cache.putIfAbsent(key, fresh);
        if (existing == null) {
            // This thread owns the computation for the key; everyone else waits on the future.
            try {
                T result = withRetries(key, task);
                fresh.complete(result);
                return result;
            } catch (DigestResolutionException e) {
                // Do not cache failures: evict so the next caller retries against the registry.
                cache.remove(key, fresh);
                fresh.completeExceptionally(e);
                throw e;
            }
        }
        try {
            return existing.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DigestResolutionException(
                    String.format("Interrupted waiting for resolution of %s.", key), e, true);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof DigestResolutionException) {
                throw (DigestResolutionException) cause;
            }
            throw new DigestResolutionException(
                    String.format("Resolution of %s failed: %s", key, cause), false);
        }
    }

    private <T> T withRetries(String key, Fallible<T> task) throws DigestResolutionException {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return task.run();
            } catch (DigestResolutionException e) {
                if (!e.isTransientFailure() || attempt >= maxAttempts) {
                    if (attempt > 1) {
                        log.warn("Resolution of {} failed after {} attempt(s): {}",
                                key, attempt, e.getMessage());
                    }
                    throw e;
                }
                log.debug("Transient failure resolving {} (attempt {}/{}): {}",
                        key, attempt, maxAttempts, e.getMessage());
            }
        }
    }

    /**
     * @return number of tag keys currently held (successful resolutions plus any in-flight)
     */
    public int cachedKeyCount() {
        return resolutions.size();
    }

    private interface Fallible<T> {
        T run() throws DigestResolutionException;
    }
}

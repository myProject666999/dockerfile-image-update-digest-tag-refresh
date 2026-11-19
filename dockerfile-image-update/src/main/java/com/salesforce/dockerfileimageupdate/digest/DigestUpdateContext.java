package com.salesforce.dockerfileimageupdate.digest;

import com.salesforce.dockerfileimageupdate.model.ImageReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Carries everything needed to update image references that pin a digest, and decides how a
 * single reference should be rewritten.
 *
 * Update rules implemented here:
 * <ul>
 *     <li>Tag and digest are updated atomically: the new tag's digest is resolved first and
 *     both are written back together. If the digest cannot be resolved, the whole reference
 *     is left untouched and the reason is reported.</li>
 *     <li>The digest kind is preserved: a reference pinning a manifest list digest gets the
 *     new tag's manifest list digest; a reference pinning a single platform's manifest
 *     digest gets the same platform's digest from the new tag. The kind of the current
 *     digest is identified against the registry; if the old digest no longer exists there,
 *     the kind cannot be determined and the most conservative action is taken: the
 *     reference is left unchanged and the situation is reported.</li>
 *     <li>Digest-only references (no tag) are left unchanged: they exist to pin exact
 *     content and there is no tag to follow.</li>
 *     <li>When the tag is unchanged but was republished to a new digest, the digest is
 *     refreshed unless the user disabled this via the command line flag.</li>
 * </ul>
 */
public class DigestUpdateContext {

    private static final Logger log = LoggerFactory.getLogger(DigestUpdateContext.class);

    private final DigestResolver resolver;
    private final boolean refreshDigestOnSameTag;
    private final List<String> report = new CopyOnWriteArrayList<>();

    public DigestUpdateContext(DigestResolver resolver, boolean refreshDigestOnSameTag) {
        this.resolver = resolver;
        this.refreshDigestOnSameTag = refreshDigestOnSameTag;
    }

    /**
     * @return a context without a resolver: references that pin a digest are left untouched
     * (and reported), plain tag-only references are updated as before
     */
    public static DigestUpdateContext withoutResolution() {
        return new DigestUpdateContext(null, false);
    }

    public boolean hasResolver() {
        return resolver != null;
    }

    public boolean isRefreshDigestOnSameTag() {
        return refreshDigestOnSameTag;
    }

    /**
     * Record a report entry for this run. Entries are logged and kept for the end-of-run
     * summary; they are also asserted on in tests.
     */
    public void report(String message) {
        log.info("{}", message);
        report.add(message);
    }

    /**
     * @return all report entries collected so far in this run
     */
    public List<String> getReport() {
        return Collections.unmodifiableList(report);
    }

    /**
     * Decide the new value for an image reference.
     *
     * @param current the reference as it appears in the file
     * @param targetTag the tag we want the image to have
     * @param location human-readable description of where the reference was found (used in
     * report entries)
     * @return the updated reference, or empty when the reference must stay exactly as it is
     */
    public Optional<ImageReference> planUpdate(ImageReference current, String targetTag, String location) {
        if (current == null || current.getName() == null) {
            return Optional.empty();
        }
        if (!current.hasDigest()) {
            // Plain tag-only reference: the historical behavior, no registry call needed.
            if (current.hasADifferentTag(targetTag)) {
                return Optional.of(current.withTag(targetTag));
            }
            return Optional.empty();
        }
        if (!current.hasTag()) {
            report(String.format("%s: '%s' pins a digest without a tag; leaving it unchanged.",
                    location, current));
            return Optional.empty();
        }
        if (resolver == null) {
            report(String.format("%s: '%s' pins a digest but no digest resolver is configured; "
                    + "leaving the whole reference unchanged.", location, current));
            return Optional.empty();
        }
        boolean sameTag = !current.hasADifferentTag(targetTag);
        if (sameTag && !refreshDigestOnSameTag) {
            return Optional.empty();
        }

        // Identify the kind of the currently pinned digest against the registry.
        TagResolution currentResolution;
        try {
            currentResolution = resolver.resolve(current.getName(), current.getTag());
        } catch (DigestResolutionException e) {
            report(String.format("%s: could not resolve current tag '%s' of '%s' to identify the "
                    + "pinned digest kind (%s); leaving the whole reference unchanged.",
                    location, current.getTag(), current, e.getMessage()));
            return Optional.empty();
        }
        Optional<DigestKind> kind = currentResolution.identify(current.getDigest());
        if (!kind.isPresent()) {
            // The pinned digest is not part of the tag's current resolution - typically
            // because the tag was republished since the digest was pinned. Ask the registry
            // what the digest itself is; manifests are served content-addressably, so this
            // still works after a republish.
            final Optional<DigestKind> identified;
            try {
                identified = resolver.identifyKind(current.getName(), current.getDigest());
            } catch (DigestResolutionException e) {
                report(String.format("%s: could not identify the kind of pinned digest '%s' of '%s' "
                        + "(%s); leaving the whole reference unchanged.",
                        location, current.getDigest(), current, e.getMessage()));
                return Optional.empty();
            }
            if (!identified.isPresent()) {
                report(String.format("%s: pinned digest '%s' of '%s' no longer exists in the registry, "
                        + "so its kind (manifest list vs platform manifest) cannot be determined; "
                        + "leaving the whole reference unchanged.", location, current.getDigest(), current));
                return Optional.empty();
            }
            kind = identified;
        }

        // Resolve the target tag (same resolution object when the tag is unchanged).
        TagResolution targetResolution = currentResolution;
        if (!sameTag) {
            try {
                targetResolution = resolver.resolve(current.getName(), targetTag);
            } catch (DigestResolutionException e) {
                report(String.format("%s: could not resolve digest for new tag '%s' of image '%s' (%s); "
                        + "leaving the whole reference unchanged.",
                        location, targetTag, current.getName(), e.getMessage()));
                return Optional.empty();
            }
        }

        Optional<String> newDigest = targetResolution.select(kind.get());
        if (!newDigest.isPresent()) {
            report(String.format("%s: new tag '%s' of image '%s' does not provide a digest of kind %s; "
                    + "leaving the whole reference unchanged.",
                    location, targetTag, current.getName(), kind.get()));
            return Optional.empty();
        }
        if (sameTag && newDigest.get().equals(current.getDigest())) {
            return Optional.empty();
        }
        if (sameTag) {
            report(String.format("%s: tag '%s' was republished; refreshing digest of '%s' to '%s'.",
                    location, targetTag, current, newDigest.get()));
        }
        return Optional.of(current.withTagAndDigest(targetTag, newDigest.get()));
    }
}

package com.salesforce.dockerfileimageupdate.digest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The result of resolving one (image, tag) pair against a registry: the digest of the tag
 * itself plus, when the tag points to a manifest list / OCI index, the digest of each
 * platform's image manifest.
 *
 * A single resolution object serves every reference style: callers that pin the manifest
 * list digest read {@link #getTopDigest()}, callers that pin a single platform read from
 * {@link #getPlatformDigests()}. Sharing one resolution across references is what keeps a
 * run consistent without forcing every reference onto the same digest value.
 */
public class TagResolution {

    private final String topDigest;
    private final boolean manifestList;
    private final Map<String, String> platformDigests;

    public TagResolution(String topDigest, boolean manifestList, Map<String, String> platformDigests) {
        this.topDigest = topDigest;
        this.manifestList = manifestList;
        this.platformDigests = Collections.unmodifiableMap(new LinkedHashMap<>(platformDigests));
    }

    /**
     * @return the digest of the tag as resolved (the manifest list digest for multi-arch
     * tags, or the manifest digest for single-image tags)
     */
    public String getTopDigest() {
        return topDigest;
    }

    public boolean isManifestList() {
        return manifestList;
    }

    /**
     * @return platform key (e.g. {@code linux/amd64}) to manifest digest; empty when the tag
     * points to a single image manifest
     */
    public Map<String, String> getPlatformDigests() {
        return platformDigests;
    }

    /**
     * Identify which kind of digest {@code digest} is within this resolution.
     *
     * @return the matching {@link DigestKind}, or empty if the digest is not part of this
     * resolution (e.g. it belonged to an older publish of the tag that no longer exists)
     */
    public Optional<DigestKind> identify(String digest) {
        if (digest != null && digest.equals(topDigest)) {
            return Optional.of(manifestList ? DigestKind.manifestList() : DigestKind.singleManifest());
        }
        for (Map.Entry<String, String> entry : platformDigests.entrySet()) {
            if (entry.getValue().equals(digest)) {
                return Optional.of(DigestKind.platform(entry.getKey()));
            }
        }
        return Optional.empty();
    }

    /**
     * Select the digest of the given kind from this resolution.
     *
     * @return the digest, or empty when this resolution cannot provide that kind (e.g. a
     * platform that no longer exists in the new tag, or a manifest list digest requested
     * from a tag that is no longer a list)
     */
    public Optional<String> select(DigestKind kind) {
        switch (kind.getType()) {
            case MANIFEST_LIST:
                return manifestList ? Optional.of(topDigest) : Optional.empty();
            case PLATFORM_MANIFEST:
                if (kind.getPlatform() == null) {
                    return manifestList ? Optional.empty() : Optional.of(topDigest);
                }
                return Optional.ofNullable(platformDigests.get(kind.getPlatform()));
            default:
                return Optional.empty();
        }
    }
}

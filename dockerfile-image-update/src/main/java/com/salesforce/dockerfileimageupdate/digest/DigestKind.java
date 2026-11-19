package com.salesforce.dockerfileimageupdate.digest;

import java.util.Objects;

/**
 * Identifies which kind of digest a reference pins, so an update can preserve the kind:
 * <ul>
 *     <li>{@link Type#MANIFEST_LIST} - the digest of the manifest list / OCI index itself</li>
 *     <li>{@link Type#PLATFORM_MANIFEST} - the digest of a single platform's image manifest.
 *     The {@code platform} key (e.g. {@code linux/amd64}) identifies which platform; it is
 *     {@code null} when the tag itself points to a single manifest rather than a list, in
 *     which case the platform is unknown but the "same kind" is still well defined.</li>
 * </ul>
 */
public class DigestKind {

    public enum Type {
        MANIFEST_LIST,
        PLATFORM_MANIFEST
    }

    private final Type type;
    private final String platform;

    private DigestKind(Type type, String platform) {
        this.type = type;
        this.platform = platform;
    }

    public static DigestKind manifestList() {
        return new DigestKind(Type.MANIFEST_LIST, null);
    }

    public static DigestKind singleManifest() {
        return new DigestKind(Type.PLATFORM_MANIFEST, null);
    }

    public static DigestKind platform(String platform) {
        return new DigestKind(Type.PLATFORM_MANIFEST, platform);
    }

    public Type getType() {
        return type;
    }

    /**
     * @return the platform key (e.g. {@code linux/amd64}) or {@code null} for manifest lists
     * and for tags that resolve to a lone manifest
     */
    public String getPlatform() {
        return platform;
    }

    @Override
    public String toString() {
        return platform == null ? type.toString() : type + "[" + platform + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DigestKind)) {
            return false;
        }
        DigestKind that = (DigestKind) o;
        return type == that.type && Objects.equals(platform, that.platform);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, platform);
    }
}

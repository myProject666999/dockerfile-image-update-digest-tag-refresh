package com.salesforce.dockerfileimageupdate.model;

import java.util.Objects;

/**
 * Represents a full image reference of the form {@code name[:tag][@digest]}.
 *
 * Examples:
 * <ul>
 *     <li>{@code nginx}</li>
 *     <li>{@code nginx:1.25}</li>
 *     <li>{@code nginx:1.25@sha256:abc...}</li>
 *     <li>{@code nginx@sha256:abc...}</li>
 *     <li>{@code my.registry.com:5000/team/nginx:1.25@sha256:abc...}</li>
 * </ul>
 *
 * The digest, when present, is what actually determines the pulled content; the tag is
 * informational. Instances are immutable.
 */
public class ImageReference {

    private final String name;
    private final String tag;
    private final String digest;

    public ImageReference(String name, String tag, String digest) {
        this.name = name;
        this.tag = tag;
        this.digest = digest;
    }

    /**
     * Parse a string of the form {@code name[:tag][@digest]} into an {@link ImageReference}.
     * The tag is only recognized when the last {@code :} appears after the last {@code /} so
     * that registry hosts with ports (e.g. {@code host:5000/img}) are not mistaken for tags.
     */
    public static ImageReference parse(String reference) {
        if (reference == null) {
            return new ImageReference(null, null, null);
        }
        String rest = reference.trim();
        String digest = null;
        String tag = null;

        int atIndex = rest.indexOf('@');
        if (atIndex >= 0 && atIndex < rest.length() - 1) {
            // Only a non-empty digest is stripped; a trailing '@' stays part of the
            // (malformed) reference so it keeps being treated as invalid.
            digest = rest.substring(atIndex + 1);
            rest = rest.substring(0, atIndex);
        }

        int lastSlash = rest.lastIndexOf('/');
        int lastColon = rest.lastIndexOf(':');
        if (lastColon > lastSlash) {
            tag = rest.substring(lastColon + 1);
            rest = rest.substring(0, lastColon);
            if (tag.isEmpty()) {
                tag = null;
            }
        }

        String name = rest.isEmpty() ? null : rest;
        return new ImageReference(name, tag, digest);
    }

    public String getName() {
        return name;
    }

    public String getTag() {
        return tag;
    }

    public String getDigest() {
        return digest;
    }

    public boolean hasTag() {
        return tag != null;
    }

    public boolean hasDigest() {
        return digest != null;
    }

    /**
     * @return a copy of this reference with the tag replaced; the digest is preserved
     */
    public ImageReference withTag(String newTag) {
        return new ImageReference(name, newTag, digest);
    }

    /**
     * @return a copy of this reference with both tag and digest replaced. Tag and digest must
     * always be written back together so the reference stays consistent.
     */
    public ImageReference withTagAndDigest(String newTag, String newDigest) {
        return new ImageReference(name, newTag, newDigest);
    }

    /**
     * Determines whether the {@code tag} of this reference differs from {@code expectedTag},
     * using the same semantics as the instruction models (nulls and surrounding whitespace).
     */
    public boolean hasADifferentTag(String expectedTag) {
        if (tag == null && expectedTag == null) {
            return false;
        }
        if (tag == null || expectedTag == null) {
            return true;
        }
        return !tag.trim().equals(expectedTag.trim());
    }

    /**
     * Check whether the image name of this reference matches {@code imageToFind} without
     * requiring the registry/other prefix details to match.
     */
    public boolean hasBaseImage(String imageToFind) {
        return name != null && imageToFind != null && name.endsWith(imageToFind);
    }

    /**
     * @return the registry host for this reference following Docker conventions: the first
     * path segment is a registry if it contains a '.' or ':' or is 'localhost'; otherwise
     * the image lives on Docker Hub.
     */
    public String getRegistryHost() {
        if (name == null) {
            return null;
        }
        String firstSegment = name.split("/")[0];
        if (firstSegment.contains(".") || firstSegment.contains(":") || firstSegment.equals("localhost")) {
            return firstSegment;
        }
        return "docker.io";
    }

    /**
     * @return the repository path inside the registry (e.g. {@code library/nginx} for the
     * Docker Hub official image {@code nginx}, or {@code team/nginx} when an explicit
     * registry is present)
     */
    public String getRepositoryPath() {
        if (name == null) {
            return null;
        }
        String firstSegment = name.split("/")[0];
        if (firstSegment.contains(".") || firstSegment.contains(":") || firstSegment.equals("localhost")) {
            return name.substring(name.indexOf('/') + 1);
        }
        if (!name.contains("/")) {
            return "library/" + name;
        }
        return name;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        if (name != null) {
            builder.append(name);
        }
        if (hasTag()) {
            builder.append(':').append(tag.trim());
        }
        if (hasDigest()) {
            builder.append('@').append(digest.trim());
        }
        return builder.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ImageReference)) {
            return false;
        }
        ImageReference that = (ImageReference) o;
        return Objects.equals(name, that.name)
                && Objects.equals(tag, that.tag)
                && Objects.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, tag, digest);
    }
}

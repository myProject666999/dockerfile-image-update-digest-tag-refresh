package com.salesforce.dockerfileimageupdate.digest;

import java.util.Optional;

/**
 * Resolves the digest(s) that a tag currently points to in a registry.
 */
public interface DigestResolver {

    /**
     * Resolve {@code tag} of {@code imageName} (which may include a registry host).
     *
     * @param imageName the image name as written in the file, e.g. {@code registry.com/team/img}
     * @param tag the tag to resolve
     * @return the resolution describing the tag's own digest and, for manifest lists, each
     * platform's digest
     * @throws DigestResolutionException when the digest cannot be resolved (missing tag,
     * authentication failure, timeout, ...). Callers must treat this as "leave the
     * reference untouched".
     */
    TagResolution resolve(String imageName, String tag) throws DigestResolutionException;

    /**
     * Identify what kind of document a digest points to by fetching the digest itself.
     * Registries serve manifests content-addressably, so this works even after the tag that
     * used to point at the digest was republished.
     *
     * @param imageName the image name as written in the file
     * @param digest the digest to look up, e.g. {@code sha256:...}
     * @return the kind of the digest ({@link DigestKind#manifestList()} or
     * {@link DigestKind#singleManifest()}), or empty when the digest no longer exists in
     * the registry
     * @throws DigestResolutionException when the registry cannot be queried
     */
    Optional<DigestKind> identifyKind(String imageName, String digest) throws DigestResolutionException;
}

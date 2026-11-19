package com.salesforce.dockerfileimageupdate.model;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class ImageReferenceTest {

    @DataProvider
    public Object[][] parseData() {
        return new Object[][]{
                // reference, name, tag, digest
                {"nginx", "nginx", null, null},
                {"nginx:1.25", "nginx", "1.25", null},
                {"nginx:1.25@sha256:abc", "nginx", "1.25", "sha256:abc"},
                {"nginx@sha256:abc", "nginx", null, "sha256:abc"},
                {"registry.com/team/nginx:1.25@sha256:abc", "registry.com/team/nginx", "1.25", "sha256:abc"},
                {"host:5000/team/nginx:1.25", "host:5000/team/nginx", "1.25", null},
                {"host:5000/team/nginx:1.25@sha256:abc", "host:5000/team/nginx", "1.25", "sha256:abc"},
                {"host:5000/team/nginx@sha256:abc", "host:5000/team/nginx", null, "sha256:abc"},
                {"image:", "image", null, null},
        };
    }

    @Test(dataProvider = "parseData")
    public void testParse(String reference, String name, String tag, String digest) {
        ImageReference parsed = ImageReference.parse(reference);
        assertEquals(parsed.getName(), name);
        assertEquals(parsed.getTag(), tag);
        assertEquals(parsed.getDigest(), digest);
    }

    @Test(dataProvider = "parseData")
    public void testRoundTrip(String reference, String name, String tag, String digest) {
        ImageReference parsed = ImageReference.parse(reference);
        if (reference.equals("image:")) {
            // A trailing colon with an empty tag normalizes away.
            assertEquals(parsed.toString(), "image");
        } else {
            assertEquals(parsed.toString(), reference);
        }
    }

    @DataProvider
    public Object[][] registryData() {
        return new Object[][]{
                {"nginx", "docker.io", "library/nginx"},
                {"team/nginx", "docker.io", "team/nginx"},
                {"registry.com/team/nginx", "registry.com", "team/nginx"},
                {"host:5000/team/nginx", "host:5000", "team/nginx"},
                {"localhost:5000/nginx", "localhost:5000", "nginx"},
        };
    }

    @Test(dataProvider = "registryData")
    public void testRegistryCoordinates(String name, String registryHost, String repositoryPath) {
        ImageReference reference = ImageReference.parse(name);
        assertEquals(reference.getRegistryHost(), registryHost);
        assertEquals(reference.getRepositoryPath(), repositoryPath);
    }

    @Test
    public void testWithTagAndDigest() {
        ImageReference reference = ImageReference.parse("nginx:1.0@sha256:old");
        ImageReference updated = reference.withTagAndDigest("2.0", "sha256:new");
        assertEquals(updated.toString(), "nginx:2.0@sha256:new");
        // The original is untouched (immutability).
        assertEquals(reference.toString(), "nginx:1.0@sha256:old");
    }

    @Test
    public void testHasTagAndDigest() {
        assertTrue(ImageReference.parse("nginx:1.0@sha256:a").hasTag());
        assertTrue(ImageReference.parse("nginx:1.0@sha256:a").hasDigest());
        assertFalse(ImageReference.parse("nginx@sha256:a").hasTag());
        assertFalse(ImageReference.parse("nginx:1.0").hasDigest());
    }

    @Test
    public void testNullReferenceParsesToNulls() {
        ImageReference parsed = ImageReference.parse(null);
        assertNull(parsed.getName());
        assertNull(parsed.getTag());
        assertNull(parsed.getDigest());
    }

    @Test
    public void testEqualsAndHashCode() {
        assertEquals(ImageReference.parse("nginx:1.0@sha256:a"), ImageReference.parse("nginx:1.0@sha256:a"));
        assertEquals(ImageReference.parse("nginx:1.0@sha256:a").hashCode(),
                ImageReference.parse("nginx:1.0@sha256:a").hashCode());
        assertFalse(ImageReference.parse("nginx:1.0").equals(ImageReference.parse("nginx:2.0")));
    }
}

package com.salesforce.dockerfileimageupdate.model;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Digest parsing tests for {@link FromInstruction} and {@link ImageKeyValuePair}.
 */
public class InstructionDigestParsingTest {

    @DataProvider
    public Object[][] fromInstructionData() {
        return new Object[][]{
                // line, name, tag, digest
                {"FROM img:1.0@sha256:abc", "img", "1.0", "sha256:abc"},
                {"FROM img@sha256:abc", "img", null, "sha256:abc"},
                {"FROM registry.com:5000/team/img:1.0@sha256:abc", "registry.com:5000/team/img", "1.0", "sha256:abc"},
                {"FROM img:1.0@sha256:abc AS builder", "img", "1.0", "sha256:abc"},
                {"FROM img:1.0@sha256:abc # comment", "img", "1.0", "sha256:abc"},
                {"FROM img:1.0", "img", "1.0", null},
        };
    }

    @Test(dataProvider = "fromInstructionData")
    public void testFromInstructionDigestParsing(String line, String name, String tag, String digest) {
        FromInstruction fromInstruction = new FromInstruction(line);
        assertEquals(fromInstruction.getBaseImageName(), name);
        assertEquals(fromInstruction.getTag(), tag);
        assertEquals(fromInstruction.getDigest(), digest);
        assertEquals(fromInstruction.hasDigest(), digest != null);
    }

    @Test(dataProvider = "fromInstructionData")
    public void testFromInstructionRoundTrip(String line, String name, String tag, String digest) {
        assertEquals(new FromInstruction(line).toString(), line);
    }

    @Test
    public void testFromInstructionWithNewTagAndDigest() {
        FromInstruction fromInstruction =
                new FromInstruction("FROM img:1.0@sha256:old AS builder # comment");
        FromInstruction updated = fromInstruction.getFromInstructionWithNewTagAndDigest("2.0", "sha256:new");
        assertEquals(updated.toString(), "FROM img:2.0@sha256:new AS builder # comment");
    }

    @Test
    public void testFromInstructionImageReference() {
        FromInstruction fromInstruction = new FromInstruction("FROM img:1.0@sha256:abc");
        assertEquals(fromInstruction.getImageReference(), ImageReference.parse("img:1.0@sha256:abc"));
    }

    @DataProvider
    public Object[][] imageKeyValuePairData() {
        return new Object[][]{
                // line, name, tag, digest
                {"image: img:1.0@sha256:abc", "img", "1.0", "sha256:abc"},
                {"image: img@sha256:abc", "img", null, "sha256:abc"},
                {"  image: registry.com:5000/team/img:1.0@sha256:abc", "registry.com:5000/team/img", "1.0", "sha256:abc"},
                {"image: img:1.0@sha256:abc # comment", "img", "1.0", "sha256:abc"},
                {"image: img:1.0", "img", "1.0", null},
        };
    }

    @Test(dataProvider = "imageKeyValuePairData")
    public void testImageKeyValuePairDigestParsing(String line, String name, String tag, String digest) {
        ImageKeyValuePair imageKeyValuePair = new ImageKeyValuePair(line);
        assertEquals(imageKeyValuePair.getBaseImageName(), name);
        assertEquals(imageKeyValuePair.getTag(), tag);
        assertEquals(imageKeyValuePair.getDigest(), digest);
        assertEquals(imageKeyValuePair.hasDigest(), digest != null);
    }

    @Test(dataProvider = "imageKeyValuePairData")
    public void testImageKeyValuePairRoundTrip(String line, String name, String tag, String digest) {
        assertEquals(new ImageKeyValuePair(line).toString(), line);
    }

    @Test
    public void testImageKeyValuePairWithNewTagAndDigest() {
        ImageKeyValuePair imageKeyValuePair = new ImageKeyValuePair("image: img:1.0@sha256:old # comment");
        ImageKeyValuePair updated = imageKeyValuePair.getImageKeyValuePairWithNewTagAndDigest("2.0", "sha256:new");
        assertEquals(updated.toString(), "image: img:2.0@sha256:new # comment");
    }

    @Test
    public void testImageKeyValuePairImageReference() {
        ImageKeyValuePair imageKeyValuePair = new ImageKeyValuePair("image: img:1.0@sha256:abc");
        assertEquals(imageKeyValuePair.getImageReference(), ImageReference.parse("img:1.0@sha256:abc"));
    }

    @Test
    public void testDigestOnlyReferenceMatchesBaseImage() {
        // A digest-only reference must still be recognized as referencing the image so the
        // updater can deliberately leave it alone (and report it).
        FromInstruction fromInstruction = new FromInstruction("FROM registry.com/team/img@sha256:abc");
        assertTrue(fromInstruction.hasBaseImage("img"));
        assertFalse(fromInstruction.hasTag());
        assertNull(fromInstruction.getTag());
    }
}

package com.salesforce.dockerfileimageupdate.model;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class ArgInstructionTest {

    @DataProvider
    public Object[][] isArgInstructionData() {
        return new Object[][]{
                {"ARG BASE_IMAGE=nginx:1.0", true},
                {"ARG TAG=1.0", true},
                {"ARG BASE_IMAGE", true},
                {"  ARG BASE_IMAGE=nginx:1.0", true},
                {"FROM nginx", false},
                {"#ARG BASE_IMAGE=nginx:1.0", false},
                {"", false},
                {null, false},
        };
    }

    @Test(dataProvider = "isArgInstructionData")
    public void testIsArgInstruction(String line, boolean expected) {
        assertEquals(ArgInstruction.isArgInstruction(line), expected);
    }

    @Test
    public void testDefaultValueParsedAsImageReference() {
        ArgInstruction arg = new ArgInstruction("ARG BASE_IMAGE=registry.com/team/img:1.0@sha256:abc");
        ImageReference defaultValue = arg.getDefaultValue();
        assertEquals(defaultValue.getName(), "registry.com/team/img");
        assertEquals(defaultValue.getTag(), "1.0");
        assertEquals(defaultValue.getDigest(), "sha256:abc");
    }

    @Test
    public void testArgWithoutDefault() {
        ArgInstruction arg = new ArgInstruction("ARG BASE_IMAGE");
        assertNull(arg.getDefaultValue());
        assertEquals(arg.toString(), "ARG BASE_IMAGE");
    }

    @Test
    public void testToStringRoundTrip() {
        String line = "ARG BASE_IMAGE=registry.com/team/img:1.0@sha256:abc # base image";
        assertEquals(new ArgInstruction(line).toString(), line);
    }

    @Test
    public void testWithDefaultValue() {
        ArgInstruction arg = new ArgInstruction("ARG BASE_IMAGE=img:1.0@sha256:old # comment");
        ArgInstruction updated = arg.withDefaultValue(ImageReference.parse("img:2.0@sha256:new"));
        assertEquals(updated.toString(), "ARG BASE_IMAGE=img:2.0@sha256:new # comment");
        // The variable declaration itself is unchanged, only the default value.
        assertTrue(updated.toString().startsWith("ARG BASE_IMAGE="));
    }

    @Test
    public void testNonImageDefaultStillParsed() {
        ArgInstruction arg = new ArgInstruction("ARG TAG=1.2.3");
        assertEquals(arg.getDefaultValue().getName(), "1.2.3");
        assertFalse(arg.getDefaultValue().hasDigest());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testInvalidLineRejected() {
        new ArgInstruction("FROM nginx");
    }
}

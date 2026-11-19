package com.salesforce.dockerfileimageupdate.model;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents an {@code ARG} instruction line in a Dockerfile whose default value may be a
 * full image reference, e.g. {@code ARG BASE_IMAGE=registry.com/img:1.0@sha256:abc}.
 *
 * When the default value is updated, only this declaration line changes; instructions that
 * consume the variable (e.g. {@code FROM ${BASE_IMAGE}}) are left untouched.
 */
public class ArgInstruction {

    private static final String NAME = "ARG";
    private static final Pattern ARG_WITH_DEFAULT =
            Pattern.compile("^(" + NAME + "\\s+[A-Za-z_][A-Za-z0-9_]*)=(\\S*)\\s*$");
    private static final String INVALID_INSTRUCTION_LINE = "You have not provided a valid ARG instruction line.";

    /**
     * The {@code ARG name} prefix, without the default value
     */
    private final String declaration;
    /**
     * The default value, parsed as an image reference; null when the ARG has no default
     */
    private final ImageReference defaultValue;
    /**
     * Comment starting with #
     */
    private final String comments;

    /**
     * Accepts an ARG instruction line from a Dockerfile.
     * See {@code isArgInstruction} to ensure you're passing a valid line in.
     *
     * @param argInstructionLine an ARG instruction line from a Dockerfile
     */
    public ArgInstruction(String argInstructionLine) {
        if (!isArgInstruction(argInstructionLine)) {
            throw new IllegalArgumentException(INVALID_INSTRUCTION_LINE);
        }
        String lineWithoutComment = argInstructionLine;
        int commentIndex = argInstructionLine.indexOf("#");
        if (commentIndex >= 0) {
            comments = argInstructionLine.substring(commentIndex);
            lineWithoutComment = argInstructionLine.substring(0, commentIndex);
        } else {
            comments = null;
        }
        String trimmed = lineWithoutComment.trim();
        Matcher matcher = ARG_WITH_DEFAULT.matcher(trimmed);
        if (matcher.matches()) {
            declaration = matcher.group(1);
            String value = matcher.group(2);
            defaultValue = value.isEmpty() ? null : ImageReference.parse(value);
        } else {
            declaration = trimmed;
            defaultValue = null;
        }
    }

    private ArgInstruction(String declaration, ImageReference defaultValue, String comments) {
        this.declaration = declaration;
        this.defaultValue = defaultValue;
        this.comments = comments;
    }

    /**
     * Determines whether the line is an ARG instruction line in a Dockerfile
     * @param dockerFileLine a single line from a Dockerfile
     * @return the line is an ARG instruction line or not
     */
    public static boolean isArgInstruction(String dockerFileLine) {
        if (StringUtils.isNotBlank(dockerFileLine)) {
            String trimmed = dockerFileLine.trim();
            return trimmed.equals(NAME) || trimmed.startsWith(NAME + " ");
        }
        return false;
    }

    /**
     * @return the default value of this ARG parsed as an {@link ImageReference}, or null
     * when the ARG declares no default
     */
    public ImageReference getDefaultValue() {
        return defaultValue;
    }

    /**
     * @return a new {@code ArgInstruction} with the default value replaced
     */
    public ArgInstruction withDefaultValue(ImageReference newDefaultValue) {
        return new ArgInstruction(declaration, newDefaultValue, comments);
    }

    public boolean hasComments() {
        return comments != null;
    }

    public String getComments() {
        return comments;
    }

    /**
     * @return a String representation of an ARG instruction line in a Dockerfile. No new
     * line at the end
     */
    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder(declaration);
        if (defaultValue != null) {
            stringBuilder.append("=").append(defaultValue);
        }
        if (hasComments()) {
            stringBuilder.append(String.format(" %s", comments));
        }
        return stringBuilder.toString();
    }
}

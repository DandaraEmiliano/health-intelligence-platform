package dev.healthintelligence.core.shared;

/**
 * One invalid field in a request. {@code message} is written by us and never echoes the submitted
 * value, so it cannot carry health data.
 */
public record FieldViolation(String field, String code, String message) {
}

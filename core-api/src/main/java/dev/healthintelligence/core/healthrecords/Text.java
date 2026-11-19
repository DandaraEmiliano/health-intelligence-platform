package dev.healthintelligence.core.healthrecords;

final class Text {

    private Text() {
    }

    /** Optional free text: trimmed, and stored as null when empty. */
    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}

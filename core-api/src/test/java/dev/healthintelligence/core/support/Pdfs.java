package dev.healthintelligence.core.support;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Tiny synthetic PDFs. Each call returns different bytes, so hashes never collide by accident. */
public final class Pdfs {

    private Pdfs() {
    }

    public static byte[] synthetic() {
        return ("%PDF-1.4\n% synthetic " + UUID.randomUUID() + "\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] encrypted() {
        return ("%PDF-1.4\n% synthetic " + UUID.randomUUID() + "\ntrailer << /Root 1 0 R /Encrypt 5 0 R >>\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
    }
}

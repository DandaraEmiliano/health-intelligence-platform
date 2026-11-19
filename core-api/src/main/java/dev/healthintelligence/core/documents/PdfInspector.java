package dev.healthintelligence.core.documents;

import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.http.HttpStatus;

/**
 * Cheap checks before a file is stored (threat model T06). The PDF is never parsed here; parsing
 * happens only in ai-service, sandboxed. This catches the common mistakes with clear errors.
 */
final class PdfInspector {

    static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
    private static final byte[] MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENCRYPT = "/Encrypt".getBytes(StandardCharsets.US_ASCII);
    /** The header may follow a few junk bytes; PDF readers accept it within the first 1 KB. */
    private static final int HEADER_WINDOW = 1024;

    private PdfInspector() {
    }

    static void check(byte[] content) {
        if (content.length == 0) {
            throw ProblemException.validation(List.of(new FieldViolation("file", "REQUIRED", "file is empty")));
        }
        if (content.length > MAX_SIZE_BYTES) {
            throw new ProblemException(HttpStatus.CONTENT_TOO_LARGE, "DOCUMENT_TOO_LARGE", "Document larger than 10 MB");
        }
        if (indexOf(content, MAGIC, Math.min(content.length, HEADER_WINDOW)) < 0) {
            throw new ProblemException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_DOCUMENT", "Only PDF files are accepted");
        }
        // Encrypted PDFs declare /Encrypt in the trailer; we cannot read them without the password.
        if (indexOf(content, ENCRYPT, content.length) >= 0) {
            throw new ProblemException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "DOCUMENT_ENCRYPTED", "Password-protected PDFs are not supported");
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int searchEnd) {
        outer:
        for (int i = 0; i <= searchEnd - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Keeps the name a user recognises, without paths or control characters. */
    static String safeFilename(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isEmpty()) {
            name = "documento.pdf";
        }
        if (name.length() > 255) {
            String extension = name.toLowerCase().endsWith(".pdf") ? ".pdf" : "";
            name = name.substring(0, 255 - extension.length()) + extension;
        }
        return name;
    }
}

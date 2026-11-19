package dev.healthintelligence.core.documents;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.healthintelligence.core.patient.CurrentPatient;
import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.IdempotencyStore;
import dev.healthintelligence.core.shared.PatientScope;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/documents")
class DocumentController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final CurrentPatient currentPatient;
    private final DocumentService documents;
    private final IdempotencyStore idempotency;
    private final JsonMapper json;

    DocumentController(CurrentPatient currentPatient, DocumentService documents, IdempotencyStore idempotency,
                       JsonMapper json) {
        this.currentPatient = currentPatient;
        this.documents = documents;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentResponse> uploadDocument(
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        if (file == null) {
            throw ProblemException.validation(List.of(new FieldViolation("file", "REQUIRED", "file is required")));
        }
        if (file.getSize() > PdfInspector.MAX_SIZE_BYTES) {
            throw new ProblemException(HttpStatus.CONTENT_TOO_LARGE, "DOCUMENT_TOO_LARGE", "Document larger than 10 MB");
        }
        PatientScope scope = currentPatient.scope();
        byte[] content = file.getBytes();
        String sha256 = sha256(content);

        if (idempotencyKey == null) {
            return accepted(DocumentResponse.of(documents.upload(scope, file.getOriginalFilename(), content, sha256)));
        }
        String key = validKey(idempotencyKey);
        String fingerprint = sha256 + ":" + file.getOriginalFilename();
        Optional<IdempotencyStore.StoredResponse> stored = idempotency.begin(scope.user(), key, fingerprint);
        if (stored.isPresent()) {
            return ResponseEntity.status(stored.get().status())
                    .location(URI.create(stored.get().location()))
                    .header("Idempotent-Replayed", "true")
                    .body(json.readValue(stored.get().body(), DocumentResponse.class));
        }
        try {
            DocumentResponse response = DocumentResponse.of(documents.upload(scope, file.getOriginalFilename(), content, sha256));
            idempotency.complete(scope.user(), key, new IdempotencyStore.StoredResponse(fingerprint,
                    HttpStatus.ACCEPTED.value(), location(response.id()).toString(), json.writeValueAsString(response)));
            return accepted(response);
        } catch (RuntimeException e) {
            idempotency.abandon(scope.user(), key);
            throw e;
        }
    }

    @GetMapping
    DocumentPage listDocuments(@RequestParam(name = "status", required = false) DocumentStatus status,
                               @RequestParam(name = "cursor", required = false) String cursor,
                               @RequestParam(name = "limit", required = false) Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw ProblemException.validation(List.of(new FieldViolation("limit", "OUT_OF_RANGE", "limit must be between 1 and 100")));
        }
        List<HealthDocument> rows = documents.page(currentPatient.scope(), status, DocumentCursor.decode(cursor), size);
        boolean more = rows.size() > size;
        List<HealthDocument> items = more ? rows.subList(0, size) : rows;
        String next = more ? new DocumentCursor(items.getLast().uploadedAt(), items.getLast().id()).encode() : null;
        return new DocumentPage(items.stream().map(DocumentResponse::of).toList(), next);
    }

    @GetMapping("/{documentId}")
    DocumentResponse getDocument(@PathVariable("documentId") UUID id) {
        return DocumentResponse.of(documents.get(currentPatient.scope(), id));
    }

    @DeleteMapping("/{documentId}")
    ResponseEntity<Void> deleteDocument(@PathVariable("documentId") UUID id) {
        documents.delete(currentPatient.scope(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 302 to a pre-signed link valid for 60 seconds. A browser app that sends the token in a header
     * cannot follow that redirect into a new tab, so with {@code Accept: application/json} the
     * link is returned in the body instead.
     */
    @GetMapping("/{documentId}/content")
    ResponseEntity<?> getDocumentContent(@PathVariable("documentId") UUID id,
                                         @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String accept) {
        DocumentService.Download download = documents.downloadUrl(currentPatient.scope(), id);
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new DownloadLink(download.url().toString(), download.expiresAt()));
        }
        return ResponseEntity.status(HttpStatus.FOUND).cacheControl(CacheControl.noStore())
                .location(URI.create(download.url().toString())).build();
    }

    private ResponseEntity<DocumentResponse> accepted(DocumentResponse response) {
        return ResponseEntity.accepted().location(location(response.id())).body(response);
    }

    private static URI location(UUID id) {
        return URI.create("/api/v1/documents/" + id);
    }

    private static String validKey(String key) {
        try {
            return UUID.fromString(key).toString();
        } catch (IllegalArgumentException invalid) {
            throw ProblemException.validation(List.of(
                    new FieldViolation("Idempotency-Key", "INVALID_FORMAT", "Idempotency-Key must be a UUID")));
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DocumentResponse(UUID id, String originalFilename, long sizeBytes, DocumentStatus status, String failureCode,
                            Instant uploadedAt) {

        static DocumentResponse of(HealthDocument document) {
            return new DocumentResponse(document.id(), document.originalFilename(), document.sizeBytes(),
                    document.status(), document.failureCode(), document.uploadedAt());
        }
    }

    record DocumentPage(List<DocumentResponse> items, String nextCursor) {
    }

    record DownloadLink(String url, Instant expiresAt) {
    }
}

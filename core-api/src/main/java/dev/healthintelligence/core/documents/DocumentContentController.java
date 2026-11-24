package dev.healthintelligence.core.documents;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves PDFs kept in the database: to the browser through a signed, short-lived link (like an
 * S3 pre-signed URL), and to ai-service by key on the internal path, which needs the internal
 * token. Present only with {@code hip.documents.store=database}.
 */
@RestController
@ConditionalOnProperty(name = "hip.documents.store", havingValue = "database")
class DocumentContentController {

    private final DatabaseDocumentStorage storage;

    DocumentContentController(DatabaseDocumentStorage storage) {
        this.storage = storage;
    }

    @GetMapping(DatabaseDocumentStorage.LINK_PATH + "{object}")
    ResponseEntity<byte[]> download(@PathVariable("object") UUID object, @RequestParam("name") String name,
                                    @RequestParam("expires") long expires, @RequestParam("signature") String signature) {
        byte[] content = storage.contentForLink(object.toString(), name, expires, signature)
                .orElseThrow(() -> ProblemException.notFound("DOCUMENT_LINK_INVALID"));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header("Content-Disposition", ContentDisposition.inline().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(content);
    }

    @GetMapping("/internal/v1/document-objects/{object}")
    ResponseEntity<byte[]> object(@PathVariable("object") UUID object) {
        byte[] content = storage.content("documents/" + object)
                .orElseThrow(() -> ProblemException.notFound("DOCUMENT_NOT_FOUND"));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore()).body(content);
    }
}

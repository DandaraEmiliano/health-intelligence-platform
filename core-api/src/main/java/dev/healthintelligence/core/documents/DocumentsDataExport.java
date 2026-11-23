package dev.healthintelligence.core.documents;

import java.util.List;

import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.PersonalDataExport;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Metadata of the documents the patient still has. The PDFs themselves are downloaded one by one
 * through {@code /documents/{id}/content}; embedding them would make the export unbounded.
 */
@Component
class DocumentsDataExport implements PersonalDataExport {

    private final DocumentRepository documents;

    DocumentsDataExport(DocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public String section() {
        return "documents";
    }

    @Override
    @Transactional(readOnly = true)
    public Object export(UserId user, PatientId patient) {
        if (patient == null) {
            return List.of();
        }
        return documents.findAllIncludingDeleted(patient).stream()
                .filter(d -> !d.isDeleted())
                .map(DocumentController.DocumentResponse::of)
                .toList();
    }
}

package dev.healthintelligence.core.patient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.healthintelligence.core.audit.AuditLog;
import dev.healthintelligence.core.audit.AuditRecord;
import dev.healthintelligence.core.identity.CurrentUser;
import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.PersonalDataExport;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The data subject's copy of everything the platform holds about them, as JSON (LGPD art. 18, II
 * and V). Each module contributes its own section; this class only assembles them. It lives in
 * the patient module because it needs the patient id, and identity must not depend on patient.
 */
@RestController
class DataExportController {

    static final String SCHEMA_VERSION = "1.0";

    private final CurrentUser currentUser;
    private final DataExports exports;

    DataExportController(CurrentUser currentUser, DataExports exports) {
        this.currentUser = currentUser;
        this.exports = exports;
    }

    record AccountExport(String schemaVersion, Instant generatedAt, String notice, Map<String, Object> data) {
    }

    @GetMapping("/api/v1/accounts/me/export")
    ResponseEntity<AccountExport> exportAccountData() {
        AccountExport export = exports.export(currentUser.id());
        String filename = "meus-dados-" + LocalDate.ofInstant(export.generatedAt(), ZoneOffset.UTC) + ".json";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(export);
    }

    /** All sections read in one transaction, so the copy is consistent, and the export audited with it. */
    @Service
    static class DataExports {

        private final PatientService patients;
        private final List<PersonalDataExport> sections;
        private final AuditLog auditLog;
        private final Clock clock;

        DataExports(PatientService patients, List<PersonalDataExport> sections, AuditLog auditLog, Clock clock) {
            this.patients = patients;
            this.sections = sections.stream().sorted(Comparator.comparing(PersonalDataExport::section)).toList();
            this.auditLog = auditLog;
            this.clock = clock;
        }

        @Transactional
        AccountExport export(UserId user) {
            PatientId patient = patients.findId(user).orElse(null);
            Map<String, Object> data = new LinkedHashMap<>();
            for (PersonalDataExport section : sections) {
                data.put(section.section(), section.export(user, patient));
            }
            auditLog.record(AuditRecord.userSuccess(user.value(), "ACCOUNT_DATA_EXPORTED", "USER_ACCOUNT", user.value(),
                    patient == null ? null : patient.value(), Map.of("sections", String.valueOf(data.size()))));
            return new AccountExport(SCHEMA_VERSION, Instant.now(clock).truncatedTo(ChronoUnit.SECONDS),
                    "Copy of your data in this platform. Uploaded PDFs are listed under documents and can be "
                            + "downloaded one by one. Security audit logs are kept separately and are not included.",
                    data);
        }
    }
}

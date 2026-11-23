package dev.healthintelligence.core.laboratory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import dev.healthintelligence.core.audit.ActorType;
import dev.healthintelligence.core.audit.AuditLog;
import dev.healthintelligence.core.audit.AuditOutcome;
import dev.healthintelligence.core.audit.AuditRecord;
import dev.healthintelligence.core.documents.ExtractionJobs;
import dev.healthintelligence.core.shared.Ids;
import dev.healthintelligence.core.shared.PatientId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stores a validated document-extraction-result.v1 message: completes the job, sets the
 * document status and, on success, writes the report and its results as PENDING_REVIEW
 * (ADR 0006). Idempotent by jobId: a second delivery finds the job completed and stores nothing.
 */
@Service
class ExtractionResultIngestion {

    enum Outcome { STORED, IGNORED }

    private final ExtractionJobs jobs;
    private final LaboratoryReportRepository reports;
    private final LaboratoryResultRepository results;
    private final AuditLog auditLog;
    private final JsonMapper json;
    private final AnalyteCatalog catalog;
    private final ApplicationEventPublisher events;

    ExtractionResultIngestion(ExtractionJobs jobs, LaboratoryReportRepository reports,
                              LaboratoryResultRepository results, AuditLog auditLog, JsonMapper json,
                              AnalyteCatalog catalog, ApplicationEventPublisher events) {
        this.catalog = catalog;
        this.events = events;
        this.jobs = jobs;
        this.reports = reports;
        this.results = results;
        this.auditLog = auditLog;
        this.json = json;
    }

    @Transactional
    Outcome ingest(JsonNode message) {
        PatientId patient = new PatientId(UUID.fromString(message.get("patientId").stringValue()));
        UUID documentId = UUID.fromString(message.get("documentId").stringValue());
        ExtractionJobs.Outcome outcome = ExtractionJobs.Outcome.valueOf(message.get("status").stringValue());
        String failureCode = message.path("failure").path("code").stringValue(null);

        var completed = jobs.complete(UUID.fromString(message.get("jobId").stringValue()), documentId, patient,
                outcome, failureCode);
        if (completed.isEmpty()) {
            return Outcome.IGNORED;
        }
        if (outcome != ExtractionJobs.Outcome.SUCCEEDED) {
            audit("DOCUMENT_EXTRACTION_" + outcome.name(), "DOCUMENT", documentId, patient,
                    failureCode == null ? Map.of() : Map.of("failureCode", failureCode));
            events.publishEvent(new ExtractionResultStored(patient, documentId, null, outcome.name()));
            return Outcome.STORED;
        }

        JsonNode report = message.get("report");
        LocalDate collectedAt = date(report.get("collectedAt"));
        LaboratoryReport saved = reports.save(new LaboratoryReport(Ids.newId(), patient, documentId,
                completed.get().jobId(), text(report.get("laboratoryName")), collectedAt,
                text(report.get("collectedAtRaw")), date(report.get("reportedAt")),
                json.writeValueAsString(report.path("warnings").isArray() ? report.get("warnings") : json.createArrayNode())));
        for (JsonNode result : report.get("results")) {
            results.save(toResult(saved.id(), patient, collectedAt, result));
        }
        audit("LABORATORY_REPORT_EXTRACTED", "LABORATORY_REPORT", saved.id(), patient,
                Map.of("documentId", documentId.toString(), "results", String.valueOf(report.get("results").size())));
        events.publishEvent(new ExtractionResultStored(patient, documentId, saved.id(), outcome.name()));
        return Outcome.STORED;
    }

    private LaboratoryResult toResult(UUID reportId, PatientId patient, LocalDate collectedAt, JsonNode result) {
        JsonNode measurement = result.get("measurement");
        JsonNode range = result.get("referenceRange");
        ArrayNode warnings = result.path("warnings").isArray()
                ? (ArrayNode) result.get("warnings").deepCopy() : json.createArrayNode();

        BigDecimal value = decimal(measurement.get("valueNumeric"));
        String comparator = text(measurement.get("comparator"));
        BigDecimal low = decimal(range.get("low"));
        BigDecimal high = decimal(range.get("high"));
        if (low != null && high != null && low.compareTo(high) > 0) {
            // Keep the printed text, drop the bounds: a range that cannot be right is not used.
            low = null;
            high = null;
            if (!hasWarning(warnings, "RANGE_LOW_ABOVE_HIGH")) {
                ObjectNode warning = json.createObjectNode();
                warning.put("code", "RANGE_LOW_ABOVE_HIGH");
                warnings.add(warning);
            }
        }
        String unit = text(result.get("unit").get("raw"));
        String kind = measurement.get("kind").stringValue();
        UUID analyteId = catalog.recognise(result.get("rawName").stringValue()).map(Analyte::id).orElse(null);
        return new LaboratoryResult(Ids.newId(), reportId, patient, collectedAt, analyteId, new ExtractedValues(
                result.get("sequence").intValue(),
                result.get("rawName").stringValue(),
                kind,
                measurement.get("rawValue").stringValue(),
                value,
                comparator,
                text(measurement.get("valueText")),
                unit,
                unit == null ? null : unit.replaceAll("\\s+", "").toLowerCase(Locale.ROOT),
                text(range.get("rawText")),
                low,
                high,
                text(range.get("appliesTo")),
                text(result.get("labFlag")),
                computedFlag(kind, value, comparator, low, high),
                text(result.get("method")),
                result.get("source").get("page").intValue(),
                result.get("source").get("excerpt").stringValue()),
                json.writeValueAsString(result), json.writeValueAsString(warnings));
    }

    /**
     * Derived from the numeric bounds only, and always labelled as computed (ADR 0007). Censored
     * values ("< 0,5") and results without bounds get none.
     */
    static String computedFlag(String kind, BigDecimal value, String comparator, BigDecimal low, BigDecimal high) {
        if (!"NUMERIC".equals(kind) || value == null || comparator != null || (low == null && high == null)) {
            return null;
        }
        if (high != null && value.compareTo(high) > 0) {
            return "HIGH";
        }
        if (low != null && value.compareTo(low) < 0) {
            return "LOW";
        }
        return "IN_RANGE";
    }

    private void audit(String action, String resourceType, UUID resourceId, PatientId patient, Map<String, String> metadata) {
        auditLog.record(new AuditRecord(null, ActorType.SYSTEM, action, resourceType, resourceId, patient.value(),
                AuditOutcome.SUCCESS, metadata));
    }

    private static boolean hasWarning(ArrayNode warnings, String code) {
        for (JsonNode warning : warnings) {
            if (code.equals(warning.path("code").stringValue(null))) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.stringValue();
    }

    private static BigDecimal decimal(JsonNode node) {
        return node == null || node.isNull() ? null : new BigDecimal(node.stringValue());
    }

    private static LocalDate date(JsonNode node) {
        return node == null || node.isNull() ? null : LocalDate.parse(node.stringValue());
    }
}

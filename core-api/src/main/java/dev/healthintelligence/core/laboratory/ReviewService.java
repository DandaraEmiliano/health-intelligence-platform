package dev.healthintelligence.core.laboratory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.healthintelligence.core.audit.AuditLog;
import dev.healthintelligence.core.audit.AuditRecord;
import dev.healthintelligence.core.shared.ETags;
import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.PatientScope;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The patient's review of an extracted report (ADR 0006). Decisions can change until the review
 * is finalized; after that the report is VERIFIED (or REJECTED) and immutable. Only VERIFIED and
 * CORRECTED results are ever used by timeline, analytics and the assistant.
 */
@Service
class ReviewService {

    private final LaboratoryReportRepository reports;
    private final LaboratoryResultRepository results;
    private final LaboratoryReports read;
    private final AuditLog auditLog;
    private final Clock clock;

    ReviewService(LaboratoryReportRepository reports, LaboratoryResultRepository results, LaboratoryReports read,
                  AuditLog auditLog, Clock clock) {
        this.reports = reports;
        this.results = results;
        this.read = read;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional
    LaboratoryReports.ReportWithResults review(PatientScope scope, UUID reportId, String ifMatch, ReviewRequest request) {
        LaboratoryReport report = reports.findForReview(reportId, scope.patient())
                .orElseThrow(() -> ProblemException.notFound(LaboratoryReports.NOT_FOUND));
        ETags.requireMatch(ifMatch, report.version());
        if (!report.isUnderReview()) {
            throw ProblemException.conflict("INVALID_STATE");
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Map<UUID, LaboratoryResult> byId = results.findByReports(List.of(report.id()), scope.patient()).stream()
                .collect(Collectors.toMap(LaboratoryResult::id, Function.identity()));

        validate(request, byId);
        int confirmed = 0;
        int corrected = 0;
        int rejected = 0;
        for (ReviewRequest.Decision decision : request.decisions()) {
            LaboratoryResult result = byId.get(decision.resultId());
            switch (decision.action()) {
                case CONFIRM -> { result.confirm(now); confirmed++; }
                case REJECT -> { result.reject(now); rejected++; }
                case CORRECT -> {
                    result.correct(corrected(result.values(), decision.correction()), now);
                    corrected++;
                    auditLog.record(AuditRecord.userSuccess(scope.user().value(), "LABORATORY_RESULT_CORRECTED",
                            "LABORATORY_RESULT", result.id(), scope.patient().value(), Map.of("reportId", report.id().toString())));
                }
            }
        }
        if (request.confirmsAllRemaining()) {
            for (LaboratoryResult result : byId.values()) {
                if ("PENDING_REVIEW".equals(result.reviewStatus())) {
                    result.confirm(now);
                    confirmed++;
                }
            }
        }
        if (request.collectedAt() != null) {
            report.correctCollectedAt(request.collectedAt());
            byId.values().forEach(result -> result.moveCollectedAt(request.collectedAt()));
        }
        auditLog.record(AuditRecord.userSuccess(scope.user().value(), "LABORATORY_REPORT_REVIEWED", "LABORATORY_REPORT",
                report.id(), scope.patient().value(), Map.of("confirmed", String.valueOf(confirmed),
                        "corrected", String.valueOf(corrected), "rejected", String.valueOf(rejected))));

        if (request.finalizes()) {
            if (byId.values().stream().anyMatch(r -> "PENDING_REVIEW".equals(r.reviewStatus()))) {
                throw ProblemException.conflict("REVIEW_INCOMPLETE");
            }
            boolean kept = byId.values().stream().anyMatch(r -> !"REJECTED".equals(r.reviewStatus()));
            report.finish(kept, now);
            auditLog.record(AuditRecord.userSuccess(scope.user().value(),
                    kept ? "LABORATORY_REPORT_VERIFIED" : "LABORATORY_REPORT_REJECTED", "LABORATORY_REPORT",
                    report.id(), scope.patient().value(), Map.of()));
        }
        reports.save(report);
        return read.get(scope, report.id());
    }

    private void validate(ReviewRequest request, Map<UUID, LaboratoryResult> byId) {
        List<FieldViolation> errors = new ArrayList<>();
        for (int i = 0; i < request.decisions().size(); i++) {
            ReviewRequest.Decision decision = request.decisions().get(i);
            String at = "decisions[" + i + "]";
            if (!byId.containsKey(decision.resultId())) {
                errors.add(new FieldViolation(at + ".resultId", "NOT_IN_REPORT", "result is not part of this report"));
            }
            boolean correcting = decision.action() == ReviewRequest.Action.CORRECT;
            if (correcting != (decision.correction() != null)) {
                errors.add(new FieldViolation(at + ".correction", correcting ? "REQUIRED" : "NOT_ALLOWED",
                        "correction is required for CORRECT and only for CORRECT"));
            }
            if (correcting && decision.correction() != null) {
                validateCorrection(at + ".correction", decision.correction(), errors);
            }
        }
        if (request.collectedAt() != null && request.collectedAt().isAfter(LocalDate.now(clock))) {
            errors.add(new FieldViolation("collectedAt", "FUTURE_DATE_NOT_ALLOWED", "collectedAt cannot be in the future"));
        }
        if (!errors.isEmpty()) {
            throw ProblemException.validation(errors);
        }
    }

    private static void validateCorrection(String at, ReviewRequest.Correction correction, List<FieldViolation> errors) {
        ReviewRequest.Measurement m = correction.measurement();
        if ("NUMERIC".equals(m.kind()) && (m.valueNumeric() == null || m.valueText() != null)) {
            errors.add(new FieldViolation(at + ".measurement", "INVALID_FORMAT", "a numeric value needs valueNumeric only"));
        }
        if ("TEXT".equals(m.kind()) && (m.valueText() == null || m.valueNumeric() != null || m.comparator() != null)) {
            errors.add(new FieldViolation(at + ".measurement", "INVALID_FORMAT", "a text value needs valueText only"));
        }
        ReviewRequest.Range r = correction.referenceRange();
        if (r.low() != null && r.high() != null && new BigDecimal(r.low()).compareTo(new BigDecimal(r.high())) > 0) {
            errors.add(new FieldViolation(at + ".referenceRange", "INVALID_PERIOD", "low must not be above high"));
        }
        if (correction.analyteId() != null) {
            errors.add(new FieldViolation(at + ".analyteId", "NOT_SUPPORTED", "analyte catalogue not available yet"));
        }
    }

    private static ExtractedValues corrected(ExtractedValues current, ReviewRequest.Correction correction) {
        ReviewRequest.Measurement m = correction.measurement();
        ReviewRequest.Range r = correction.referenceRange();
        BigDecimal value = m.valueNumeric() == null ? null : new BigDecimal(m.valueNumeric());
        BigDecimal low = r.low() == null ? null : new BigDecimal(r.low());
        BigDecimal high = r.high() == null ? null : new BigDecimal(r.high());
        String unit = correction.unit() == null || correction.unit().isBlank() ? null : correction.unit().strip();
        return new ExtractedValues(current.sequence(), current.rawName(), m.kind(), m.rawValue().strip(), value,
                m.comparator(), m.valueText(), unit,
                unit == null ? null : unit.replaceAll("\\s+", "").toLowerCase(Locale.ROOT),
                r.rawText(), low, high, r.appliesTo(), current.labFlag(),
                ExtractionResultIngestion.computedFlag(m.kind(), value, m.comparator(), low, high),
                current.method(), current.sourcePage(), current.sourceExcerpt());
    }
}

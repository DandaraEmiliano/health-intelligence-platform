package dev.healthintelligence.core.laboratory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import dev.healthintelligence.core.shared.PatientScope;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side for the patient: their reports and results, never anyone else's. */
@Service
class LaboratoryReports {

    static final String NOT_FOUND = "LABORATORY_REPORT_NOT_FOUND";

    record ReportWithResults(LaboratoryReport report, List<LaboratoryResult> results) {
    }

    private final LaboratoryReportRepository reports;
    private final LaboratoryResultRepository results;

    LaboratoryReports(LaboratoryReportRepository reports, LaboratoryResultRepository results) {
        this.reports = reports;
        this.results = results;
    }

    @Transactional(readOnly = true)
    List<ReportWithResults> page(PatientScope scope, String status, ReportCursor after, int limit) {
        List<LaboratoryReport> page = reports.page(scope.patient(), status, after == null ? null : after.collectedAt(),
                after == null ? null : after.id(), Limit.of(limit + 1));
        return withResults(scope, page);
    }

    @Transactional(readOnly = true)
    long countPendingReview(PatientScope scope) {
        return reports.countPendingReview(scope.patient());
    }

    @Transactional(readOnly = true)
    ReportWithResults get(PatientScope scope, UUID id) {
        LaboratoryReport report = reports.findVisible(id, scope.patient())
                .orElseThrow(() -> ProblemException.notFound(NOT_FOUND));
        return withResults(scope, List.of(report)).getFirst();
    }

    private List<ReportWithResults> withResults(PatientScope scope, List<LaboratoryReport> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<LaboratoryResult>> byReport = results
                .findByReports(page.stream().map(LaboratoryReport::id).toList(), scope.patient()).stream()
                .collect(Collectors.groupingBy(LaboratoryResult::reportId));
        return page.stream().map(r -> new ReportWithResults(r, byReport.getOrDefault(r.id(), List.of()))).toList();
    }
}

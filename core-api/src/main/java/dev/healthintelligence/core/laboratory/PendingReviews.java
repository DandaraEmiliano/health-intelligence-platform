package dev.healthintelligence.core.laboratory;

import dev.healthintelligence.core.patient.CurrentPatient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** How many reports wait for the patient's review, for a badge on the home page. */
@RestController
class PendingReviews {

    private final CurrentPatient currentPatient;
    private final LaboratoryReports reports;

    PendingReviews(CurrentPatient currentPatient, LaboratoryReports reports) {
        this.currentPatient = currentPatient;
        this.reports = reports;
    }

    record Count(long reports) {
    }

    @GetMapping("/api/v1/laboratory-reports/pending-review/count")
    Count countPendingLaboratoryReviews() {
        return new Count(reports.countPendingReview(currentPatient.scope()));
    }
}

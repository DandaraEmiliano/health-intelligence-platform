package dev.healthintelligence.core.laboratory;

import java.util.List;

import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.PatientScope;
import dev.healthintelligence.core.shared.PersonalDataExport;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Every report with every result in any review state, including what was extracted before a correction. */
@Component
class LaboratoryDataExport implements PersonalDataExport {

    private final LaboratoryReports reports;
    private final ResultViews views;

    LaboratoryDataExport(LaboratoryReports reports, ResultViews views) {
        this.reports = reports;
        this.views = views;
    }

    @Override
    public String section() {
        return "laboratoryReports";
    }

    @Override
    @Transactional(readOnly = true)
    public Object export(UserId user, PatientId patient) {
        if (patient == null) {
            return List.of();
        }
        return reports.all(new PatientScope(user, patient)).stream().map(views::detail).toList();
    }
}

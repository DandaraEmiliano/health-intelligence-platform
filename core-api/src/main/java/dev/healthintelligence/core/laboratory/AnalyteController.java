package dev.healthintelligence.core.laboratory;

import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AnalyteController {

    private final AnalyteCatalog catalog;

    AnalyteController(AnalyteCatalog catalog) {
        this.catalog = catalog;
    }

    record AnalyteView(UUID id, String code, String displayName, String category) {

        static AnalyteView of(Analyte analyte) {
            return new AnalyteView(analyte.id(), analyte.code(), analyte.displayName(), analyte.category());
        }
    }

    record Items(List<AnalyteView> items) {
    }

    @GetMapping("/api/v1/analytes")
    Items searchAnalytes(@RequestParam(name = "query", required = false) String query) {
        if (query == null || query.strip().length() < 2 || query.length() > 100) {
            throw ProblemException.validation(List.of(new FieldViolation("query", "INVALID_LENGTH", "query must have 2 to 100 characters")));
        }
        return new Items(catalog.search(query, 20).stream().map(AnalyteView::of).toList());
    }
}

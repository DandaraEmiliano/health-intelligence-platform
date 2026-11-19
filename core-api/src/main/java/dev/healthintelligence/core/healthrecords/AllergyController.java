package dev.healthintelligence.core.healthrecords;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.patient.CurrentPatient;
import dev.healthintelligence.core.shared.ETags;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/patients/me/allergies")
class AllergyController {

    private final CurrentPatient currentPatient;
    private final HealthRecordService records;
    private final AllergyKind kind;

    AllergyController(CurrentPatient currentPatient, HealthRecordService records, AllergyKind kind) {
        this.currentPatient = currentPatient;
        this.records = records;
        this.kind = kind;
    }

    @GetMapping
    Items<AllergyResponse> listAllergies() {
        return new Items<>(records.list(kind, currentPatient.scope()).stream().map(AllergyResponse::of).toList());
    }

    @PostMapping
    ResponseEntity<AllergyResponse> createAllergy(@Valid @RequestBody AllergyRequest request) {
        Allergy created = records.create(kind, currentPatient.scope(), request);
        return ResponseEntity.created(URI.create("/api/v1/patients/me/allergies/" + created.id()))
                .eTag(ETags.of(created.version())).body(AllergyResponse.of(created));
    }

    @GetMapping("/{allergyId}")
    ResponseEntity<AllergyResponse> getAllergy(@PathVariable("allergyId") UUID id) {
        Allergy record = records.get(kind, currentPatient.scope(), id);
        return ResponseEntity.ok().eTag(ETags.of(record.version())).body(AllergyResponse.of(record));
    }

    @PutMapping("/{allergyId}")
    ResponseEntity<AllergyResponse> replaceAllergy(@PathVariable("allergyId") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody AllergyRequest request) {
        Allergy record = records.replace(kind, currentPatient.scope(), id, ifMatch, request);
        return ResponseEntity.ok().eTag(ETags.of(record.version())).body(AllergyResponse.of(record));
    }

    @DeleteMapping("/{allergyId}")
    ResponseEntity<Void> deleteAllergy(@PathVariable("allergyId") UUID id) {
        records.delete(kind, currentPatient.scope(), id);
        return ResponseEntity.noContent().build();
    }

    record Items<T>(List<T> items) {
    }
}

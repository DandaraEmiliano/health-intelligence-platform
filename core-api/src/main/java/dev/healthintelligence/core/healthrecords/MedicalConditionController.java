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
@RequestMapping("/api/v1/patients/me/conditions")
class MedicalConditionController {

    private final CurrentPatient currentPatient;
    private final HealthRecordService records;
    private final MedicalConditionKind kind;

    MedicalConditionController(CurrentPatient currentPatient, HealthRecordService records, MedicalConditionKind kind) {
        this.currentPatient = currentPatient;
        this.records = records;
        this.kind = kind;
    }

    @GetMapping
    Items<MedicalConditionResponse> listConditions() {
        return new Items<>(records.list(kind, currentPatient.scope()).stream().map(MedicalConditionResponse::of).toList());
    }

    @PostMapping
    ResponseEntity<MedicalConditionResponse> createCondition(@Valid @RequestBody MedicalConditionRequest request) {
        MedicalCondition created = records.create(kind, currentPatient.scope(), request);
        return ResponseEntity.created(URI.create("/api/v1/patients/me/conditions/" + created.id()))
                .eTag(ETags.of(created.version())).body(MedicalConditionResponse.of(created));
    }

    @GetMapping("/{conditionId}")
    ResponseEntity<MedicalConditionResponse> getCondition(@PathVariable("conditionId") UUID id) {
        MedicalCondition record = records.get(kind, currentPatient.scope(), id);
        return ResponseEntity.ok().eTag(ETags.of(record.version())).body(MedicalConditionResponse.of(record));
    }

    @PutMapping("/{conditionId}")
    ResponseEntity<MedicalConditionResponse> replaceCondition(@PathVariable("conditionId") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody MedicalConditionRequest request) {
        MedicalCondition record = records.replace(kind, currentPatient.scope(), id, ifMatch, request);
        return ResponseEntity.ok().eTag(ETags.of(record.version())).body(MedicalConditionResponse.of(record));
    }

    @DeleteMapping("/{conditionId}")
    ResponseEntity<Void> deleteCondition(@PathVariable("conditionId") UUID id) {
        records.delete(kind, currentPatient.scope(), id);
        return ResponseEntity.noContent().build();
    }

    record Items<T>(List<T> items) {
    }
}

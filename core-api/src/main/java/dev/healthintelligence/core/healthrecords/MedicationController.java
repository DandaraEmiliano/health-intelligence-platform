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
@RequestMapping("/api/v1/patients/me/medications")
class MedicationController {

    private final CurrentPatient currentPatient;
    private final HealthRecordService records;
    private final MedicationKind kind;

    MedicationController(CurrentPatient currentPatient, HealthRecordService records, MedicationKind kind) {
        this.currentPatient = currentPatient;
        this.records = records;
        this.kind = kind;
    }

    @GetMapping
    Items<MedicationResponse> listMedications() {
        return new Items<>(records.list(kind, currentPatient.scope()).stream().map(MedicationResponse::of).toList());
    }

    @PostMapping
    ResponseEntity<MedicationResponse> createMedication(@Valid @RequestBody MedicationRequest request) {
        Medication created = records.create(kind, currentPatient.scope(), request);
        return ResponseEntity.created(URI.create("/api/v1/patients/me/medications/" + created.id()))
                .eTag(ETags.of(created.version())).body(MedicationResponse.of(created));
    }

    @GetMapping("/{medicationId}")
    ResponseEntity<MedicationResponse> getMedication(@PathVariable("medicationId") UUID id) {
        Medication record = records.get(kind, currentPatient.scope(), id);
        return ResponseEntity.ok().eTag(ETags.of(record.version())).body(MedicationResponse.of(record));
    }

    @PutMapping("/{medicationId}")
    ResponseEntity<MedicationResponse> replaceMedication(@PathVariable("medicationId") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody MedicationRequest request) {
        Medication record = records.replace(kind, currentPatient.scope(), id, ifMatch, request);
        return ResponseEntity.ok().eTag(ETags.of(record.version())).body(MedicationResponse.of(record));
    }

    @DeleteMapping("/{medicationId}")
    ResponseEntity<Void> deleteMedication(@PathVariable("medicationId") UUID id) {
        records.delete(kind, currentPatient.scope(), id);
        return ResponseEntity.noContent().build();
    }

    record Items<T>(List<T> items) {
    }
}

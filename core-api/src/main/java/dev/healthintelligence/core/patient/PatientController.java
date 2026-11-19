package dev.healthintelligence.core.patient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.healthintelligence.core.identity.CurrentUser;
import dev.healthintelligence.core.shared.ETags;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Patient profile of the authenticated user. The patient is never identified by the client (ADR 0009). */
@RestController
@RequestMapping("/api/v1/patients")
class PatientController {

    static final URI ME = URI.create("/api/v1/patients/me");

    private final CurrentUser currentUser;
    private final PatientService patients;

    PatientController(CurrentUser currentUser, PatientService patients) {
        this.currentUser = currentUser;
        this.patients = patients;
    }

    @PostMapping
    ResponseEntity<PatientResponse> createPatient(@RequestBody PatientCreateRequest request) {
        Patient patient = patients.create(currentUser.id(), request.toProfile());
        return ResponseEntity.created(ME).eTag(ETags.of(patient.version())).body(PatientResponse.of(patient));
    }

    @GetMapping("/me")
    ResponseEntity<PatientResponse> getMyPatient() {
        Patient patient = patients.get(currentUser.id());
        return ResponseEntity.ok().eTag(ETags.of(patient.version())).body(PatientResponse.of(patient));
    }

    @PatchMapping(path = "/me", consumes = "application/merge-patch+json")
    ResponseEntity<PatientResponse> updateMyPatient(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody JsonNode patch) {
        Patient patient = patients.update(currentUser.id(), ifMatch, PatientPatch.parse(patch));
        return ResponseEntity.ok().eTag(ETags.of(patient.version())).body(PatientResponse.of(patient));
    }

    record PatientCreateRequest(String fullName, LocalDate birthDate, BiologicalSex biologicalSex,
                                BigDecimal heightCm) {

        PatientProfile toProfile() {
            return new PatientProfile(fullName, birthDate, biologicalSex, heightCm);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PatientResponse(UUID id, String fullName, LocalDate birthDate, BiologicalSex biologicalSex,
                           BigDecimal heightCm, Instant createdAt, Instant updatedAt) {

        static PatientResponse of(Patient patient) {
            PatientProfile profile = patient.profile();
            return new PatientResponse(patient.id(), profile.fullName(), profile.birthDate(),
                    profile.biologicalSex(), profile.heightCm(), patient.createdAt(), patient.updatedAt());
        }
    }
}

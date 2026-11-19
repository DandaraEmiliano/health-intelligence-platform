package dev.healthintelligence.core.consent;

import java.time.Instant;
import java.util.List;

import dev.healthintelligence.core.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Consents belong to the user account, so they can be given during onboarding before the profile exists. */
@RestController
@RequestMapping("/api/v1/consents")
class ConsentController {

    private final CurrentUser currentUser;
    private final ConsentService consents;

    ConsentController(CurrentUser currentUser, ConsentService consents) {
        this.currentUser = currentUser;
        this.consents = consents;
    }

    @GetMapping
    ConsentList getConsents() {
        return new ConsentList(consents.current(currentUser.id()).stream().map(ConsentState::of).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ConsentState recordConsent(@Valid @RequestBody ConsentRequest request) {
        return ConsentState.of(consents.record(currentUser.id(), request.type(), request.granted(),
                request.documentVersion()));
    }

    record ConsentRequest(
            @NotNull ConsentType type,
            @NotNull Boolean granted,
            @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String documentVersion) {
    }

    record ConsentState(ConsentType type, boolean granted, String documentVersion, Instant recordedAt) {

        static ConsentState of(ConsentRecord record) {
            return new ConsentState(record.consentType(), record.granted(), record.documentVersion(),
                    record.recordedAt());
        }
    }

    record ConsentList(List<ConsentState> items) {
    }
}

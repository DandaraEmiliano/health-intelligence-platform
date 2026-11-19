package dev.healthintelligence.core.consent;

import dev.healthintelligence.core.shared.UserId;
import org.springframework.stereotype.Component;

/** What other modules may ask about consent. The latest record of each type decides. */
@Component
public class Consents {

    private final ConsentService service;

    Consents(ConsentService service) {
        this.service = service;
    }

    public boolean isGranted(UserId user, ConsentType type) {
        return service.isGranted(user, type);
    }
}

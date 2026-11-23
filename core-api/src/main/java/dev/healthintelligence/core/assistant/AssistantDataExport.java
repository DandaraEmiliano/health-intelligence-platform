package dev.healthintelligence.core.assistant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.PatientScope;
import dev.healthintelligence.core.shared.PersonalDataExport;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Every conversation with every message, as the patient saw them. */
@Component
class AssistantDataExport implements PersonalDataExport {

    /** No practical cap: one patient's history; the limit only guards against a runaway query. */
    static final int MAX = 100_000;

    record ExportedConversation(UUID id, String title, Instant createdAt, Instant updatedAt, List<Object> messages) {
    }

    private final AssistantService assistant;

    AssistantDataExport(AssistantService assistant) {
        this.assistant = assistant;
    }

    @Override
    public String section() {
        return "assistantConversations";
    }

    @Override
    @Transactional(readOnly = true)
    public Object export(UserId user, PatientId patient) {
        if (patient == null) {
            return List.of();
        }
        PatientScope scope = new PatientScope(user, patient);
        return assistant.page(scope, null, MAX).stream()
                .map(c -> new ExportedConversation(c.id(), c.title(), c.createdAt(), c.updatedAt(),
                        assistant.messages(scope, c.id(), null, MAX).stream().map(assistant::view).toList()))
                .toList();
    }
}

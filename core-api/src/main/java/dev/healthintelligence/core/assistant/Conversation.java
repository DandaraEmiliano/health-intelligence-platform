package dev.healthintelligence.core.assistant;

import java.time.Instant;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A patient's conversation with the assistant. Its messages are deleted with it (ON DELETE CASCADE). */
@Entity
@Table(name = "ai_conversation")
class Conversation {

    static final int TITLE_MAX = 200;

    @Id
    private UUID id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private PatientId patientId;

    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Conversation() {
    }

    Conversation(UUID id, PatientId patientId, String title, Instant now) {
        this.id = id;
        this.patientId = patientId;
        this.title = title;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** After a new exchange: moves to the top of the list, and gets a title from the first question. */
    void exchanged(String question, Instant now) {
        if (title == null) {
            title = titleFrom(question);
        }
        updatedAt = now;
    }

    static String titleFrom(String question) {
        String single = question.strip().replaceAll("\\s+", " ");
        return single.length() <= 80 ? single : single.substring(0, 79).strip() + "…";
    }

    UUID id() { return id; }
    PatientId patientId() { return patientId; }
    String title() { return title; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
}

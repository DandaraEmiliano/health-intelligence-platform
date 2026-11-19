package dev.healthintelligence.core.laboratory;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Catalogue entry (reference data, not patient data). */
@Entity
@Immutable
@Table(name = "analyte")
class Analyte {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String code;

    @Column(name = "display_name_pt", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String category;

    @Column(name = "default_unit")
    private String defaultUnit;

    protected Analyte() {
    }

    UUID id() { return id; }
    String code() { return code; }
    String displayName() { return displayName; }
    String category() { return category; }
}

package dev.healthintelligence.core.laboratory;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "analyte_alias")
class AnalyteAlias {

    @Id
    private UUID id;

    @Column(name = "analyte_id", nullable = false)
    private UUID analyteId;

    @Column(name = "alias_normalized", nullable = false)
    private String aliasNormalized;

    protected AnalyteAlias() {
    }

    UUID analyteId() { return analyteId; }
    String aliasNormalized() { return aliasNormalized; }
}

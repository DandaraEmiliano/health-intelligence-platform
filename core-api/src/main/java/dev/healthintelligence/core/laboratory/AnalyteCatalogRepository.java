package dev.healthintelligence.core.laboratory;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;

/**
 * Reference data shared by all patients, so its queries take no owner. It is not a clinical
 * repository: it lives here only because laboratory owns the catalogue.
 */
interface AnalyteCatalogRepository extends org.springframework.data.repository.Repository<Analyte, UUID> {

    @Query("select a from Analyte a")
    List<Analyte> findAllAnalytes();

    @Query("select a from AnalyteAlias a")
    List<AnalyteAlias> findAllAliases();
}

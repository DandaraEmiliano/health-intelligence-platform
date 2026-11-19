package dev.healthintelligence.core.documents;

import java.util.UUID;

import org.springframework.data.repository.Repository;

interface ExtractionJobRepository extends Repository<ExtractionJob, UUID> {

    ExtractionJob save(ExtractionJob job);
}

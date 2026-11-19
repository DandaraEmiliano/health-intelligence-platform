/**
 * Allergies, medications and medical conditions declared by the patient.
 *
 * <p>Phase 4: CRUD under {@code /api/v1/patients/me/{allergies,medications,conditions}}, every lookup scoped by
 * {@link dev.healthintelligence.core.shared.PatientId}. Medications are free text: the platform never
 * suggests or changes dosages.
 */
@ApplicationModule(displayName = "Health records")
package dev.healthintelligence.core.healthrecords;

import org.springframework.modulith.ApplicationModule;

package dev.healthintelligence.core.shared;

/**
 * One module's part of the data subject's export (LGPD art. 18, II and V). Each module that holds
 * personal data implements it for its own data, so the export never reaches into another module.
 */
public interface PersonalDataExport {

    /** Key of this section in the export, e.g. {@code "healthRecords"}. */
    String section();

    /**
     * Everything this module holds about the owner, as plain JSON-ready values.
     *
     * @param patient null when the account has no patient profile yet
     */
    Object export(UserId user, PatientId patient);
}

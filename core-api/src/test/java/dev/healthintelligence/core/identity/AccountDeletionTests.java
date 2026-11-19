package dev.healthintelligence.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import dev.healthintelligence.core.support.IntegrationTest;
import dev.healthintelligence.core.support.Pdfs;
import dev.healthintelligence.core.support.RecordingIdentityProviderUsers;
import dev.healthintelligence.core.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.json.JsonMapper;

class AccountDeletionTests extends IntegrationTest {

    @Autowired TestTokens.Issuer tokens;
    @Autowired JsonMapper json;
    @Autowired AccountPurgeJob purgeJob;
    @Autowired RecordingIdentityProviderUsers identityProvider;
    @Autowired S3Client s3;

    String subject;
    String token;
    String patientId;
    String keptDocument;
    String deletedDocument;

    /** A patient with a bit of everything: consent, profile, allergy, two documents (one deleted). */
    @BeforeEach
    void patientWithData() {
        subject = UUID.randomUUID().toString();
        token = tokens.patient(subject, subject + "@example.test");
        api().postJson("/api/v1/consents", token, "{\"type\":\"AI_PROCESSING\",\"granted\":true,\"documentVersion\":\"ai-2026.1\"}");
        patientId = json.readTree(api().postJson("/api/v1/patients", token, "{\"fullName\":\"Ana\",\"birthDate\":\"1990-01-01\"}").body())
                .get("id").stringValue();
        api().postJson("/api/v1/patients/me/allergies", token, "{\"substance\":\"Penicilina\",\"allergyType\":\"MEDICATION\"}");
        keptDocument = json.readTree(api().upload("/api/v1/documents", token, "a.pdf", Pdfs.synthetic()).body()).get("id").stringValue();
        deletedDocument = json.readTree(api().upload("/api/v1/documents", token, "b.pdf", Pdfs.synthetic()).body()).get("id").stringValue();
        api().delete("/api/v1/documents/" + deletedDocument, token);
    }

    @Test
    void requestBlocksAccessAtOnceAndCanBeRepeated() throws Exception {
        assertThat(api().delete("/api/v1/accounts/me", token).statusCode()).isEqualTo(202);

        HttpResponse<String> blocked = api().get("/api/v1/patients/me", token);
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(json.readTree(blocked.body()).get("code").stringValue()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(api().get("/api/v1/documents", token).statusCode()).isEqualTo(403);
        assertThat(api().delete("/api/v1/accounts/me", token).statusCode()).isEqualTo(202);

        assertThat(count("select count(*) from core.user_account where idp_subject = ? and status = 'DELETION_REQUESTED'", subject)).isEqualTo(1);
        assertThat(count("""
                select count(*) from core.audit_event a join core.user_account u on u.id = a.actor_user_id
                where u.idp_subject = ? and a.action = 'ACCOUNT_DELETION_REQUESTED'""", subject)).isEqualTo(1);
    }

    @Test
    void purgeRemovesRowsFilesAndTheIdentityProviderUserButKeepsTheAuditTrail() throws Exception {
        String userId = single("select id::text from core.user_account where idp_subject = ?", subject);
        api().delete("/api/v1/accounts/me", token);

        purgeJob.purgeDue();

        assertThat(count("select count(*) from core.user_account where id = ?::uuid", userId)).isZero();
        assertThat(count("select count(*) from core.patient where id = ?::uuid", patientId)).isZero();
        assertThat(count("select count(*) from core.allergy where patient_id = ?::uuid", patientId)).isZero();
        assertThat(count("select count(*) from core.health_document where patient_id = ?::uuid", patientId)).isZero();
        assertThat(count("select count(*) from core.extraction_job where patient_id = ?::uuid", patientId)).isZero();
        assertThat(count("select count(*) from core.consent_record where user_id = ?::uuid", userId)).isZero();
        assertThat(stored(keptDocument)).isFalse();
        assertThat(stored(deletedDocument)).isFalse();
        assertThat(identityProvider.deleted()).contains(subject);

        assertThat(count("select count(*) from core.audit_event where resource_id = ?::uuid and action = 'ACCOUNT_PURGED' and actor_type = 'SYSTEM'", userId))
                .isEqualTo(1);
        assertThat(count("select count(*) from core.audit_event where actor_user_id = ?::uuid and action = 'PATIENT_PROFILE_CREATED'", userId))
                .isEqualTo(1);
    }

    @Test
    void otherPatientsAreUntouched() throws Exception {
        String other = tokens.newPatient();
        api().postJson("/api/v1/patients", other, "{\"fullName\":\"Bia\",\"birthDate\":\"1991-01-01\"}");
        String otherDocument = json.readTree(api().upload("/api/v1/documents", other, "c.pdf", Pdfs.synthetic()).body()).get("id").stringValue();
        api().delete("/api/v1/accounts/me", token);

        purgeJob.purgeDue();

        assertThat(api().get("/api/v1/patients/me", other).statusCode()).isEqualTo(200);
        assertThat(api().get("/api/v1/documents/" + otherDocument, other).statusCode()).isEqualTo(200);
        assertThat(stored(otherDocument)).isTrue();
    }

    @Test
    void aFailedStepLeavesTheAccountForTheNextRun() throws Exception {
        api().delete("/api/v1/accounts/me", token);
        identityProvider.failOnceFor(subject);

        purgeJob.purgeDue();

        assertThat(count("select count(*) from core.user_account where idp_subject = ? and status = 'DELETION_REQUESTED'", subject)).isEqualTo(1);
        assertThat(count("select count(*) from core.patient where id = ?::uuid", patientId)).isEqualTo(1);

        purgeJob.purgeDue();

        assertThat(count("select count(*) from core.user_account where idp_subject = ?", subject)).isZero();
        assertThat(identityProvider.deleted()).contains(subject);
    }

    private boolean stored(String documentId) {
        try {
            s3.headObject(r -> r.bucket("hip-documents-local").key("documents/" + documentId));
            return true;
        } catch (S3Exception notFound) {
            assertThat(notFound.statusCode()).isEqualTo(404);
            return false;
        }
    }

    private static long count(String sql, String parameter) throws Exception {
        return Long.parseLong(single(sql, parameter));
    }

    private static String single(String sql, String parameter) throws Exception {
        try (Connection connection = connect("core_owner", CORE_OWNER_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}

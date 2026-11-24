package dev.healthintelligence.core.identity;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.Ids;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E-mail and password accounts in {@code core.local_credential}, hashed with BCrypt. Plays the
 * identity provider's part when there is none: the subject it hands out is what the account is
 * provisioned with (CurrentUser), and deleting it ends sign-in for good.
 */
@Component
@ConditionalOnProperty(name = "hip.auth.mode", havingValue = "local")
class LocalCredentials {

    static final int PASSWORD_MIN = 10;
    static final int PASSWORD_MAX = 128;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    /** Compared against when the e-mail is unknown, so both cases take the same time. */
    private final String decoy = encoder.encode(UUID.randomUUID().toString());

    LocalCredentials(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    LocalUser register(String email, String password) {
        String normalized = normalize(email);
        List<FieldViolation> errors = new java.util.ArrayList<>();
        if (normalized == null || normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
            errors.add(new FieldViolation("email", "INVALID_FORMAT", "email is not valid"));
        }
        if (password == null || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            errors.add(new FieldViolation("password", "OUT_OF_RANGE", "password must have 10 to 128 characters"));
        } else if (password.toLowerCase(Locale.ROOT).contains(String.valueOf(normalized).split("@")[0])) {
            errors.add(new FieldViolation("password", "TOO_WEAK", "password must not contain the e-mail"));
        }
        if (!errors.isEmpty()) {
            throw ProblemException.validation(errors);
        }
        String subject = "local:" + Ids.newId();
        try {
            jdbc.update("insert into core.local_credential (subject, email_normalized, password_hash) values (?, ?, ?)",
                    subject, normalized, encoder.encode(password));
        } catch (DuplicateKeyException taken) {
            throw ProblemException.conflict("EMAIL_ALREADY_REGISTERED");
        }
        return new LocalUser(subject, normalized);
    }

    Optional<LocalUser> authenticate(String email, String password) {
        String normalized = normalize(email);
        List<String[]> rows = normalized == null ? List.of() : jdbc.query(
                "select subject, password_hash from core.local_credential where email_normalized = ?",
                (row, n) -> new String[] {row.getString(1), row.getString(2)}, normalized);
        if (password == null || password.length() > PASSWORD_MAX) {
            return Optional.empty();
        }
        if (rows.isEmpty()) {
            encoder.matches(password, decoy);
            return Optional.empty();
        }
        return encoder.matches(password, rows.getFirst()[1])
                ? Optional.of(new LocalUser(rows.getFirst()[0], normalized))
                : Optional.empty();
    }

    void delete(String subject) {
        jdbc.update("delete from core.local_credential where subject = ?", subject);
    }

    static String normalize(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return Normalizer.normalize(email.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}

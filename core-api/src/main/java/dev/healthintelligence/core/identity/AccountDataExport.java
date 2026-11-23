package dev.healthintelligence.core.identity;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.PersonalDataExport;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class AccountDataExport implements PersonalDataExport {

    record Account(UUID id, String email, String status, List<String> roles) {
    }

    private final UserAccountRepository accounts;

    AccountDataExport(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public String section() {
        return "account";
    }

    @Override
    @Transactional(readOnly = true)
    public Object export(UserId user, PatientId patient) {
        return accounts.findById(user.value())
                .map(a -> new Account(a.id(), a.email(), a.status().name(),
                        a.roles().stream().map(Enum::name).sorted(Comparator.naturalOrder()).toList()))
                .orElse(null);
    }
}

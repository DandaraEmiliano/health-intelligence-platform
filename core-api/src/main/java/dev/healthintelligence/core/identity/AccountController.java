package dev.healthintelligence.core.identity;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController {

    private final CurrentUser currentUser;
    private final AccountService accounts;

    AccountController(CurrentUser currentUser, AccountService accounts) {
        this.currentUser = currentUser;
        this.accounts = accounts;
    }

    /** 202: access is blocked now; data, files and the identity provider user are purged later. */
    @DeleteMapping("/me")
    ResponseEntity<Void> requestAccountDeletion() {
        accounts.requestDeletion(currentUser.resolve().id());
        return ResponseEntity.accepted().build();
    }
}

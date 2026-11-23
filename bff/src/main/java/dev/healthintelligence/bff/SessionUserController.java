package dev.healthintelligence.bff;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who is signed in, for the SPA to choose between the landing page and the app. No tokens. */
@RestController
class SessionUserController {

    record SessionUser(String email, String name) {
    }

    @GetMapping("/bff/user")
    ResponseEntity<SessionUser> user(@AuthenticationPrincipal OidcUser user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SessionUser(user.getEmail(), user.getFullName()));
    }
}

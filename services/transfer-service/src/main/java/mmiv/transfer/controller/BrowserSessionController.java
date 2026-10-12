package mmiv.transfer.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import mmiv.transfer.config.TransferSecurityConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/session")
public class BrowserSessionController {
    private final boolean secureCookie;

    public BrowserSessionController(@Value("${mmiv.auth.cookie-secure:false}") boolean secureCookie) {
        this.secureCookie = secureCookie;
    }

    @PostMapping
    public ResponseEntity<Void> establish(@AuthenticationPrincipal Jwt jwt) {
        // The filter chain verifies this token before this method can issue a cookie.
        long remaining = Math.max(0, Duration.between(Instant.now(), jwt.getExpiresAt()).getSeconds());
        if (remaining == 0) {
            return ResponseEntity.status(401).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,
                cookie(jwt.getTokenValue(), Math.min(1800, remaining)).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping
    public Map<String, String> current(@AuthenticationPrincipal Jwt jwt, CsrfToken csrf) {
        return Map.of("userId", jwt.getSubject(), "csrfToken", csrf.getToken(),
                "csrfHeader", csrf.getHeaderName(), "expiresAt", jwt.getExpiresAt().toString());
    }

    @DeleteMapping
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private ResponseCookie cookie(String token, long maxAge) {
        return ResponseCookie.from(TransferSecurityConfig.AUTH_COOKIE, token)
                .httpOnly(true).secure(secureCookie).sameSite("Strict").path("/").maxAge(maxAge).build();
    }
}

package mmiv.user_access_service.controller;

import mmiv.user_access_service.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deletes only the caller's randomly named verification account. */
@RestController
public class VerificationAccountController {
  private final UserRepository users;

  public VerificationAccountController(UserRepository users) {
    this.users = users;
  }

  @DeleteMapping("/api/auth/verification-account")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.status(users.deleteVerificationAccount(jwt.getSubject()) ? 204 : 403)
        .header("Cache-Control", "no-store").build();
  }
}

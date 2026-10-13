package mmiv.user_access_service.controller;

import java.util.*;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.repository.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/access/verification")
public class VerificationFolderController {
  private final UserRepository users;
  private final FolderRepository folders;

  public VerificationFolderController(UserRepository users, FolderRepository folders) {
    this.users = users;
    this.folders = folders;
  }

  private boolean owned(Jwt jwt, String name) {
    var user =
        users
            .findById(jwt.getSubject())
            .filter(u -> u.isActive())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    return user.getUsername().matches("mmiv_ci_[0-9a-f]{32}")
        && "USER".equals(user.getRole())
        && folders.exists(name)
        && user.getDestinationAccesses().stream()
            .anyMatch(
                a -> name.equals(a.getDestination()) && a.getPermission() == Permission.WRITE);
  }

  @GetMapping("/{run}/folder")
  public Object folder(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID run) {
    String name = "mmiv-verification-" + run;
    return Map.of("owned", owned(jwt, name), "name", name);
  }

  @PostMapping("/{run}/cleanup")
  public ResponseEntity<Void> cleanup(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID run) {
    String name = "mmiv-verification-" + run;
    if (folders.exists(name)
        && (!owned(jwt, name) || !folders.deleteVerification(name, jwt.getSubject())))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Verification folder ownership required");
    return ResponseEntity.noContent().build();
  }
}

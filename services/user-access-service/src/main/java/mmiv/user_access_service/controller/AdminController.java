package mmiv.user_access_service.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.service.AdminService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final AdminService admins;
  public AdminController(AdminService admins) { this.admins = admins; }

  @ModelAttribute
  void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

  @GetMapping("/me")
  public AdminService.AdminIdentity identity(@AuthenticationPrincipal Jwt jwt) { return admins.identity(jwt); }

  @GetMapping("/users")
  public AdminService.UserDetails lookup(@AuthenticationPrincipal Jwt jwt, @RequestParam String username) {
    return admins.lookup(jwt, username);
  }

  @PutMapping("/users/{userId}/destinations")
  public AdminService.UserDetails grant(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId,
      @Valid @RequestBody Grant request) {
    return admins.grant(jwt, userId, request.destination(), request.permission());
  }

  @DeleteMapping("/users/{userId}/destinations")
  public AdminService.UserDetails revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId,
      @RequestParam String destination) {
    return admins.revoke(jwt, userId, destination);
  }

  @PostMapping("/invitations")
  public AdminService.Invitation invite(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Invite request) {
    return admins.createInvitation(jwt, request.username());
  }

  @PostMapping("/invitations/accept")
  public AdminService.AdminIdentity accept(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Acceptance request) {
    return admins.acceptInvitation(jwt, request.token());
  }

  public record Invite(@NotBlank @Size(max=40) String username) {}
  public record Acceptance(@NotBlank @Size(min=64,max=64) String token) {}

  public record Grant(@NotBlank @Size(max=254) String destination, @NotNull Permission permission) {}
}

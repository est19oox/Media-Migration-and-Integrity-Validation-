package mmiv.user_access_service.controller;
import java.util.*;
import java.nio.charset.StandardCharsets;
import mmiv.user_access_service.repository.UserRepository;
import mmiv.user_access_service.service.AdminService;
import mmiv.events.EventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
@RestController
public class PasswordResetController {
  private final UserRepository users;private final PasswordEncoder passwords;private final EventPublisher events;
  public PasswordResetController(UserRepository users,PasswordEncoder passwords,EventPublisher events){this.users=users;this.passwords=passwords;this.events=events;}
  public record Reset(String token,String password){}
  @PostMapping("/api/auth/password-reset") public ResponseEntity<?> reset(@RequestBody Reset request){
    if(request.token()==null||!request.token().matches("[0-9a-f]{64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid or expired reset link");
    if(request.password()==null||request.password().isBlank()||request.password().length()<8||request.password().getBytes(StandardCharsets.UTF_8).length>72)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use at least 8 characters and no more than 72 UTF-8 bytes");
    String id=users.consumePasswordReset(AdminService.tokenHash(request.token()),()->passwords.encode(request.password()),java.time.Instant.now()).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid or expired reset link"));
    events.audit("PASSWORD_RESET_COMPLETED",Map.of("actorId",id));return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("message","Password changed. Sign in with your new password."));
  }
}

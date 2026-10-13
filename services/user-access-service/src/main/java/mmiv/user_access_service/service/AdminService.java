package mmiv.user_access_service.service;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import mmiv.events.EventPublisher;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminService {
  private final UserRepository users;
  private final EventPublisher events;
  private final String destination;
  @Value("${MMIV_BUCKET_NAME:media-migration-dev-landing}") private String bucket;
  private final mmiv.user_access_service.repository.FolderRepository folders;

  public AdminService(UserRepository users, EventPublisher events, mmiv.user_access_service.repository.FolderRepository folders,
      @Value("${mmiv.admin.destination-id:${MMIV_DESTINATION_ID:${MMIV_BUCKET_NAME:media-migration-dev-landing}}}") String destination) {
    this.users = users;
    this.events = events;
    this.destination = destination;
    this.folders = folders;
  }

  public AdminIdentity identity(Jwt jwt) {
    return authorized(jwt, () -> new AdminIdentity(jwt.getSubject(), destination));
  }

  public UserDetails lookup(Jwt jwt, String username) {
    return authorized(jwt, () -> details(users.findByUsername(username)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"))));
  }

  @Transactional
  public UserDetails grant(Jwt jwt, String userId, String requestedDestination, Permission permission) {
    return authorized(jwt, () -> {
      validateDestination(requestedDestination);
      if (permission == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Permission is required");
      User target = target(userId);
      if (!target.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "User account is inactive");
      users.grantDestinationAccess(userId, requestedDestination, permission);
      audit("ADMIN_PERMISSION_GRANTED", jwt.getSubject(), userId, permission.name(),requestedDestination);
      return details(target(userId));
    });
  }

  @Transactional
  public UserDetails revoke(Jwt jwt, String userId, String requestedDestination) {
    return authorized(jwt, () -> {
      validateDestination(requestedDestination);
      target(userId);
      users.revokeDestinationAccess(userId, requestedDestination);
      audit("ADMIN_PERMISSION_REVOKED", jwt.getSubject(), userId, "NONE",requestedDestination);
      return details(target(userId));
    });
  }

  private <T> T authorized(Jwt jwt, Supplier<T> operation) {
    try {
      if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank())
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required");
      User actor = users.findById(jwt.getSubject())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required"));
      // JWT role claims may be stale. Current database role and active status govern every request.
      if (!actor.isActive() || !"ADMIN".equals(actor.getRole()))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required");
      return operation.get();
    } catch (DataAccessException error) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Account service unavailable");
    }
  }

  @Transactional
  public Invitation createInvitation(Jwt jwt, String username) {
    return authorized(jwt, () -> {
      User user = users.findByUsername(username)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
      if (!user.isActive() || !"USER".equals(user.getRole()) || user.getUsername().matches("mmiv_ci_[0-9a-f]{32}"))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Invitation requires an active ordinary user");
      byte[] bytes = new byte[32];
      new java.security.SecureRandom().nextBytes(bytes);
      String token = java.util.HexFormat.of().formatHex(bytes);
      var expires = java.time.Instant.now().plusSeconds(86400);
      users.createAdminInvitation(user.getUserId(), tokenHash(token), expires, jwt.getSubject());
      audit("ADMIN_INVITATION_CREATED", jwt.getSubject(), user.getUserId(), "ADMIN");
      return new Invitation(token, expires);
    });
  }

  @Transactional
  public AdminIdentity acceptInvitation(Jwt jwt, String token) {
    try {
      if (jwt == null || jwt.getSubject() == null || token == null || !token.matches("[0-9a-f]{64}"))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or expired invitation");
      User user = users.findById(jwt.getSubject())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or expired invitation"));
      if (!user.isActive() || user.getUsername().matches("mmiv_ci_[0-9a-f]{32}")
          || !users.acceptAdminInvitation(user.getUserId(), tokenHash(token), java.time.Instant.now()))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or expired invitation");
      audit("ADMIN_INVITATION_ACCEPTED", user.getUserId(), user.getUserId(), "ADMIN");
      return new AdminIdentity(user.getUserId(), destination);
    } catch (DataAccessException error) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Account service unavailable");
    }
  }


  @Transactional public Invitation createPasswordReset(Jwt jwt,String id){return authorized(jwt,()->{
    User target=target(id);if(!target.isActive())throw new ResponseStatusException(HttpStatus.CONFLICT,"Account is inactive");
    if("ADMIN".equals(target.getRole())&&!id.equals(jwt.getSubject()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You cannot reset another administrator's password");
    byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);String token=java.util.HexFormat.of().formatHex(bytes);var expires=java.time.Instant.now().plusSeconds(3600);
    if(!users.createPasswordReset(jwt.getSubject(),id,tokenHash(token),expires))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account cannot be managed");
    audit("ADMIN_PASSWORD_RESET_CREATED",jwt.getSubject(),id,"PASSWORD_RESET");return new Invitation(token,expires);
  });}
  @Transactional public void deleteAccount(Jwt jwt,String id){authorized(jwt,()->{
    User target=target(id);if("ADMIN".equals(target.getRole())&&!id.equals(jwt.getSubject()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You cannot delete another administrator");
    if(!users.deleteAccount(jwt.getSubject(),id))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account cannot be deactivated");
    audit("ADMIN_ACCOUNT_DEACTIVATED",jwt.getSubject(),id,"INACTIVE");return null;
  });}
  public static String tokenHash(String token) {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
  }

  public record Invitation(String token, java.time.Instant expiresAt) {}

  private void validateDestination(String value) {
    if (mmiv.user_access_service.repository.FolderRepository.verification(value) || !folders.lock(value))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown folder");
  }

  public List<UserDetails> search(Jwt jwt, String query) {
    return authorized(jwt, () -> users.searchUsers(query == null ? "" : query).stream().map(this::details).toList());
  }
  public List<String> folders(Jwt jwt) { return authorized(jwt, folders::list); }
  public List<String> createFolder(Jwt jwt, String name) {
    return authorized(jwt, () -> {
      if (!mmiv.user_access_service.repository.FolderRepository.valid(name) || mmiv.user_access_service.repository.FolderRepository.verification(name) || bucket.equals(name))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a folder name of up to 80 letters, numbers, spaces, dots, underscores or hyphens");
      folders.create(name); events.audit("ADMIN_FOLDER_CREATED", Map.of("actorId", jwt.getSubject(), "folder", name));
      return folders.list();
    });
  }
  @Transactional public List<String> deleteFolder(Jwt jwt,String name){return authorized(jwt,()->{
    validateDestination(name);folders.delete(name);events.audit("ADMIN_FOLDER_DELETED",Map.of("actorId",jwt.getSubject(),"folder",name));return folders.list();
  });}

  private User target(String userId) {
    return users.findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
  }

  private UserDetails details(User user) {
    return new UserDetails(user.getUserId(), user.getUsername(), user.getRole(), user.isActive(), user.getDestinationAccesses());
  }

  private void audit(String operation, String actor, String target, String permission) {
    audit(operation,actor,target,permission,destination);
  }
  private void audit(String operation, String actor, String target, String permission,String folder) {
    Runnable publish = () -> events.audit(operation,
        Map.of("actorId", actor, "targetUserId", target, "destination", folder, "permission", permission));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { publish.run(); }
      });
    } else publish.run();
  }

  public record AdminIdentity(String userId, String destination) {}
  public record UserDetails(String userId, String username, String role, boolean active,
      List<DestinationAccess> destinations) {}
}

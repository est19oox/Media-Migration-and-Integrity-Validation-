package mmiv.user_access_service.repository;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Optional;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryUserRepository implements UserRepository {

  private record Invitation(String userId, java.time.Instant expiresAt,String issuer) {}
  private final Map<String, Invitation> invitations = new ConcurrentHashMap<>();

  @Override
  public synchronized void createAdminInvitation(String userId, String tokenHash, java.time.Instant expiresAt, String issuerId) {
    if (invitations.putIfAbsent(tokenHash, new Invitation(userId, expiresAt,issuerId)) != null)
      throw new org.springframework.dao.DuplicateKeyException("Invitation already exists");
  }

  @Override
  public synchronized boolean acceptAdminInvitation(String userId, String tokenHash, java.time.Instant now) {
    var invitation = invitations.get(tokenHash);
    var user = users.get(userId);
    if (invitation == null || !invitation.userId().equals(userId) || !invitation.expiresAt().isAfter(now)
        || user == null || !user.isActive() || !"USER".equals(user.getRole())) return false;
    invitations.entrySet().removeIf(entry -> entry.getValue().userId().equals(userId));
    resets.entrySet().removeIf(entry -> entry.getValue().userId().equals(userId));
    users.put(userId, new User(userId, user.getDestinationAccesses(), user.getUsername(), "ADMIN",
        true, user.getPermissions(), user.getPasswordHash(),user.getAuthVersion()));
    return true;
  }

  private final Map<String, User> users = new ConcurrentHashMap<>();

  public java.util.List<User> searchUsers(String query) {
    return users.values().stream().filter(user -> user.getUsername().toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT)))
        .sorted(java.util.Comparator.comparing(User::getUsername)).limit(100).toList();
  }

  @Override
  public synchronized void create(User user) {
    if (users.containsKey(user.getUserId()) || findByUsername(user.getUsername()).isPresent()) {
      throw new org.springframework.dao.DuplicateKeyException("User already exists");
    }
    users.put(user.getUserId(), user);
  }

  @Override
  public void save(User user) {
    User existing = users.get(user.getUserId());
    if (existing == null) {
      users.put(user.getUserId(), user);
      return;
    }

    users.put(
        user.getUserId(),
        new User(
            user.getUserId(),
            existing.getDestinationAccesses(),
            user.getUsername(),
            user.getRole(),
            user.isActive(),
            user.getPermissions(),
            user.getPasswordHash(),existing.getAuthVersion()));
  }

  @Override
  public Optional<User> findById(String userId) {
    return Optional.ofNullable(users.get(userId));
  }

  @Override
  public boolean deleteVerificationAccount(String userId) {
    User user = users.get(userId);
    return user != null && "USER".equals(user.getRole())
        && user.getUsername() != null && user.getUsername().matches("mmiv_ci_[0-9a-f]{32}")
        && users.remove(userId, user);
  }

  @Override
  public Optional<User> findByUsername(String username) {
    if (username == null) {
      return Optional.empty();
    }

    return users.values().stream().filter(user -> username.equals(user.getUsername())).findFirst();
  }

  @Override
  public void grantDestinationAccess(String userId, String destination, Permission permission) {
    User user = users.get(userId);
    if (user == null) {
      throw new IllegalArgumentException("Unknown user: " + userId);
    }
    if (destination == null || destination.isBlank() || permission == null) {
      throw new IllegalArgumentException("Destination and permission are required");
    }

    java.util.List<DestinationAccess> accesses =
        new java.util.ArrayList<>(user.getDestinationAccesses());
    accesses.removeIf(access -> destination.equals(access.getDestination()));
    accesses.add(new DestinationAccess(destination, permission));
    users.put(
        userId,
        new User(
            userId,
            accesses,
            user.getUsername(),
            user.getRole(),
            user.isActive(),
            user.getPermissions(),
            user.getPasswordHash(),user.getAuthVersion()));
  }

  @Override
  public void revokeDestinationAccess(String userId, String destination) {
    User user = users.get(userId);
    if (user == null) {
      throw new IllegalArgumentException("Unknown user: " + userId);
    }
    if (destination == null || destination.isBlank()) {
      throw new IllegalArgumentException("Destination is required");
    }

    java.util.List<DestinationAccess> accesses =
        new java.util.ArrayList<>(user.getDestinationAccesses());
    accesses.removeIf(access -> destination.equals(access.getDestination()));
    users.put(
        userId,
        new User(
            userId,
            accesses,
            user.getUsername(),
            user.getRole(),
            user.isActive(),
            user.getPermissions(),
            user.getPasswordHash(),user.getAuthVersion()));
  }
  private record Reset(String userId,String issuer,java.time.Instant expires){}
  private final Map<String,Reset> resets=new ConcurrentHashMap<>();
  private boolean managementAllowed(String actor,String target,boolean activeTarget){var a=users.get(actor);var t=users.get(target);return a!=null&&a.isActive()&&"ADMIN".equals(a.getRole())&&t!=null&&(!activeTarget||t.isActive())&&(actor.equals(target)||"USER".equals(t.getRole()));}
  public synchronized boolean deleteAccount(String actor,String target){
    if(!managementAllowed(actor,target,false))return false;
    invitations.replaceAll((key,value)->value.userId().equals(target)||value.issuer().equals(target)?new Invitation(value.userId(),java.time.Instant.EPOCH,value.issuer()):value);
    resets.replaceAll((key,value)->value.userId().equals(target)||value.issuer().equals(target)?new Reset(value.userId(),value.issuer(),java.time.Instant.EPOCH):value);
    var user=users.get(target);
    users.put(target,new User(target,user.getDestinationAccesses(),user.getUsername(),user.getRole(),false,user.getPermissions(),user.getPasswordHash(),user.getAuthVersion()+1));return true;
  }
  public synchronized boolean createPasswordReset(String actor,String target,String hash,java.time.Instant expires){if(!managementAllowed(actor,target,true))return false;resets.entrySet().removeIf(e->(e.getValue().userId().equals(target)||e.getValue().issuer().equals(target)));resets.put(hash,new Reset(target,actor,expires));return true;}
  public synchronized Optional<String> consumePasswordReset(String hash,java.util.function.Supplier<String> password,java.time.Instant now){var reset=resets.get(hash);if(reset==null||!reset.expires().isAfter(now))return Optional.empty();var user=users.get(reset.userId());if(user==null||!user.isActive())return Optional.empty();String encoded=password.get();resets.entrySet().removeIf(e->e.getValue().userId().equals(user.getUserId()));invitations.entrySet().removeIf(e->e.getValue().userId().equals(user.getUserId()));users.put(user.getUserId(),new User(user.getUserId(),user.getDestinationAccesses(),user.getUsername(),user.getRole(),true,user.getPermissions(),encoded,user.getAuthVersion()+1));return Optional.of(user.getUserId());}

}

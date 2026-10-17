package mmiv.user_access_service.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserAggregateTest {
  private User user(String id, String role) {
    return new User(
        id,
        List.of(new DestinationAccess("SW001", Permission.WRITE)),
        id,
        role,
        true,
        Set.of(),
        "hash",
        4);
  }

  @Test
  void deactivationRetainsGrantsAndRevokesSessionsAndAccess() {
    var original = user("owner", "USER");
    var disabled = original.deactivate();
    assertFalse(disabled.isActive());
    assertFalse(disabled.accessTo("SW001", "READ").authorised());
    assertEquals(5, disabled.getAuthVersion());
    assertEquals(original.getDestinationAccesses(), disabled.getDestinationAccesses());
    assertTrue(original.isActive());
  }

  @Test
  void onlySelfOrAnOrdinaryUserCanBeManagedByAnActiveAdministrator() {
    var admin = user("admin", "ADMIN");
    assertTrue(admin.canBeManagedBy(admin, true));
    assertTrue(user("user", "USER").canBeManagedBy(admin, true));
    assertFalse(user("other", "ADMIN").canBeManagedBy(admin, false));
    assertFalse(user("user", "USER").canBeManagedBy(admin.deactivate(), false));
  }

  @Test
  void passwordResetRevokesSessionsWithoutChangingRoleOrGrants() {
    var user = user("owner", "USER");
    var changed = user.resetPassword("newHash");
    assertEquals(5, changed.getAuthVersion());
    assertEquals("newHash", changed.getPasswordHash());
    assertEquals(user.getDestinationAccesses(), changed.getDestinationAccesses());
    assertThrows(IllegalStateException.class, () -> user.deactivate().resetPassword("newHash"));
  }

  @Test
  void grantsAreFolderScopedAndWriteIncludesRead() {
    var user = user("owner", "USER");
    assertTrue(user.accessTo("SW001", "READ").authorised());
    assertFalse(user.accessTo("SW002", "READ").authorised());
    var reader = user.grant("SW001", Permission.READ);
    assertTrue(reader.accessTo("SW001", "READ").authorised());
    assertFalse(reader.accessTo("SW001", "WRITE").authorised());
    assertFalse(reader.revoke("SW001").accessTo("SW001", "READ").authorised());
  }

  @Test
  void verificationAccountsCannotReceiveAdministratorInvitations() {
    assertFalse(user("mmiv_ci_" + "a".repeat(32), "USER").canReceiveInvitation());
    assertTrue(user("ordinary", "USER").canReceiveInvitation());
    assertFalse(user("ordinary", "USER").deactivate().canReceiveInvitation());
  }
}

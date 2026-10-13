package mmiv.user_access_service.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PermissionTest {

  @Test
  void readPermissionShouldAllowRead() {
    assertTrue(Permission.READ.allows(Action.READ));
  }

  @Test
  void readPermissionShouldDenyWrite() {
    assertFalse(Permission.READ.allows(Action.WRITE));
  }

  @Test
  void readWritePermissionShouldAllowRead() {
    assertTrue(Permission.WRITE.allows(Action.READ));
  }

  @Test
  void readWritePermissionShouldAllowWrite() {
    assertTrue(Permission.WRITE.allows(Action.WRITE));
  }
}

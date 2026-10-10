package mmiv.user_access_service.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class PermissionTest {

  @Test
  void shouldContainReadAndWritePermissions() {
    assertArrayEquals(new Permission[] {Permission.READ, Permission.WRITE}, Permission.values());
  }
}

package mmiv.user_access_service.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class PermissionTest {

    @Test
    void shouldContainReadAndWritePermissions() {
        assertArrayEquals(
                new Permission[] {Permission.READ, Permission.WRITE},
                Permission.values());
    }
}

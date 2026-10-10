package mmiv.user_access_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import mmiv.user_access_service.dto.AccessCheckRequest;
import mmiv.user_access_service.dto.AccessCheckResponse;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;
import org.junit.jupiter.api.Test;

class AccessServiceTest {

  @Test
  void shouldAllowReadWhenUserHasReadPermissionForDestination() {
    User user = new User("U001", List.of(new DestinationAccess("mmiv-bucket-a", Permission.READ)));
    UserRepository userRepository = mock(UserRepository.class);
    when(userRepository.findById("U001")).thenReturn(Optional.of(user));

    AccessCheckRequest request = request("U001", "mmiv-bucket-a", "READ");

    AccessCheckResponse response = new AccessService(userRepository).checkAccess(request);

    assertTrue(response.isAuthorised());
  }

  @Test
  void shouldAllowWriteWhenUserHasReadWritePermission() {

    User alice =
        new User("U001", List.of(new DestinationAccess("mmiv-bucket-a", Permission.WRITE)));

    UserRepository userRepository = mock(UserRepository.class);

    when(userRepository.findById("U001")).thenReturn(Optional.of(alice));

    AccessService accessService = new AccessService(userRepository);

    AccessCheckRequest request = request("U001", "mmiv-bucket-a", "WRITE");

    AccessCheckResponse response = accessService.checkAccess(request);

    assertTrue(response.isAuthorised());
  }

  @Test
  void shouldDenyWriteWhenUserOnlyHasReadPermission() {

    User bob = new User("U002", List.of(new DestinationAccess("mmiv-bucket-a", Permission.READ)));

    UserRepository userRepository = mock(UserRepository.class);

    when(userRepository.findById("U002")).thenReturn(Optional.of(bob));

    AccessService accessService = new AccessService(userRepository);

    AccessCheckRequest request = request("U002", "mmiv-bucket-a", "WRITE");

    AccessCheckResponse response = accessService.checkAccess(request);

    assertFalse(response.isAuthorised());
  }

  @Test
  void shouldDenyAccessToUnauthorizedDestination() {
    User user = new User("U001", List.of(new DestinationAccess("mmiv-bucket-a", Permission.WRITE)));
    UserRepository userRepository = mock(UserRepository.class);
    when(userRepository.findById("U001")).thenReturn(Optional.of(user));

    AccessCheckRequest request = request("U001", "mmiv-bucket-b", "READ");

    AccessCheckResponse response = new AccessService(userRepository).checkAccess(request);

    assertFalse(response.isAuthorised());
    assertEquals("No access to destination", response.getReason());
  }

  @Test
  void shouldNotUseGlobalPermissionForUnassignedDestination() {
    User alice =
        new User(
            "U001", "alice", "OPERATOR", true, java.util.Set.of(Permission.WRITE), "bcrypt-hash");

    UserRepository userRepository = mock(UserRepository.class);
    when(userRepository.findById("U001")).thenReturn(Optional.of(alice));

    AccessCheckRequest request = request("U001", "mmiv-bucket-a", "WRITE");

    AccessCheckResponse response = new AccessService(userRepository).checkAccess(request);

    assertFalse(response.isAuthorised());
    assertEquals("No access to destination", response.getReason());
  }

  private static AccessCheckRequest request(String userId, String destination, String action) {
    AccessCheckRequest request = new AccessCheckRequest();
    request.setUserId(userId);
    request.setDestination(destination);
    request.setAction(action);
    return request;
  }
}

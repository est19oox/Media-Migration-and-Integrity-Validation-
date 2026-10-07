package mmiv.user_access_service.service;

import mmiv.user_access_service.dto.AccessCheckRequest;
import mmiv.user_access_service.dto.AccessCheckResponse;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessServiceTest {

    @Test
    void shouldAllowReadWhenActiveUserHasReadPermission() {
        UserRepository repository = repositoryWith(new User(
                "U001", "alice", "analyst", true, Set.of(Permission.READ)));

        AccessCheckResponse response = service(repository).checkAccess(request("U001", "READ"));

        assertTrue(response.isAuthorised());
    }

    @Test
    void shouldAllowWriteWhenActiveUserHasWritePermission() {
        UserRepository repository = repositoryWith(new User(
                "U002", "bob", "operator", true, Set.of(Permission.WRITE)));

        AccessCheckResponse response = service(repository).checkAccess(request("U002", "WRITE"));

        assertTrue(response.isAuthorised());
    }

    @Test
    void shouldDenyWriteWhenUserDoesNotHaveWritePermission() {
        UserRepository repository = repositoryWith(new User(
                "U003", "carol", "reader", true, Set.of(Permission.READ)));

        AccessCheckResponse response = service(repository).checkAccess(request("U003", "WRITE"));

        assertFalse(response.isAuthorised());
    }

    @Test
    void shouldDenyInactiveUser() {
        UserRepository repository = repositoryWith(new User(
                "U004", "dave", "operator", false, Set.of(Permission.WRITE)));

        AccessCheckResponse response = service(repository).checkAccess(request("U004", "WRITE"));

        assertFalse(response.isAuthorised());
    }

    @Test
    void shouldDenyUnknownUser() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findById("U999")).thenReturn(Optional.empty());

        AccessCheckResponse response = service(repository).checkAccess(request("U999", "READ"));

        assertFalse(response.isAuthorised());
    }

    private static UserRepository repositoryWith(User user) {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findById(user.getUserId())).thenReturn(Optional.of(user));
        return repository;
    }

    private static AccessService service(UserRepository repository) {
        return new AccessService(repository);
    }

    private static AccessCheckRequest request(String userId, String action) {
        AccessCheckRequest request = new AccessCheckRequest();
        request.setUserId(userId);
        request.setAction(action);
        return request;
    }
}

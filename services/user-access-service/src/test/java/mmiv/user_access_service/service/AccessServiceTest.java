package mmiv.user_access_service.service;

import mmiv.user_access_service.dto.AccessCheckRequest;
import mmiv.user_access_service.dto.AccessCheckResponse;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessServiceTest {

    @Test
    void shouldAllowWriteWhenUserHasReadWritePermission() {

        User alice = new User(
                "U001",
                List.of(
                        new DestinationAccess(
                                "mmiv-bucket-a",
                                Permission.READ_WRITE)));

        UserRepository userRepository = userId -> Optional.of(alice);

        AccessService accessService = new AccessService(userRepository);

        AccessCheckRequest request = new AccessCheckRequest();
        request.setUserId("U001");
        request.setDestination("mmiv-bucket-a");
        request.setAction("WRITE");

        AccessCheckResponse response = accessService.checkAccess(request);

        assertTrue(response.isAuthorised());
    }

    @Test
    void shouldDenyWriteWhenUserOnlyHasReadPermission() {

        User bob = new User(
                "U002",
                List.of(
                        new DestinationAccess(
                                "mmiv-bucket-a",
                                Permission.READ)));

        UserRepository userRepository = userId -> Optional.of(bob);

        AccessService accessService = new AccessService(userRepository);

        AccessCheckRequest request = new AccessCheckRequest();
        request.setUserId("U002");
        request.setDestination("mmiv-bucket-a");
        request.setAction("WRITE");

        AccessCheckResponse response = accessService.checkAccess(request);

        assertFalse(response.isAuthorised());
    }
}
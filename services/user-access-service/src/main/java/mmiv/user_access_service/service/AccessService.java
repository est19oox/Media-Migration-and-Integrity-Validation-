package mmiv.user_access_service.service;

import mmiv.user_access_service.dto.AccessCheckRequest;
import mmiv.user_access_service.dto.AccessCheckResponse;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service 
public class AccessService {

    private final UserRepository userRepository;

    public AccessService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AccessCheckResponse checkAccess(AccessCheckRequest request) {
        User user = userRepository.findById(request.getUserId()).orElse(null);
        if (user == null) {
            return new AccessCheckResponse(
                    false,
                    "User not found");
        }

        if (!user.isActive()) {
            return new AccessCheckResponse(
                    false,
                    "User is inactive");
        }

        Permission permission;
        try {
            permission = Permission.valueOf(request.getAction().toUpperCase());
        } catch (IllegalArgumentException e) {
            return new AccessCheckResponse(
                    false,
                    "Invalid action");
        }

        if (user.hasPermission(permission)) {
            return new AccessCheckResponse(
                    true,
                    "Access granted");
        }

        return new AccessCheckResponse(
                false,
                "Permission denied");
    }
}
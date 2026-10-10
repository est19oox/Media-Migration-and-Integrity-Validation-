package mmiv.user_access_service.service;

import java.util.Optional;
import mmiv.user_access_service.dto.AccessCheckRequest;
import mmiv.user_access_service.dto.AccessCheckResponse;
import mmiv.user_access_service.model.Action;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class AccessService {

  private final UserRepository userRepository;

  public AccessService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * Checks destination-specific access only. User-level permissions used for authentication or JWT
   * claims do not grant access to a destination.
   */
  public AccessCheckResponse checkAccess(AccessCheckRequest request) {

    // 1. Find user
    Optional<User> userOptional = userRepository.findById(request.getUserId());

    if (userOptional.isEmpty()) {
      return new AccessCheckResponse(false, "User not found");
    }

    User user = userOptional.get();

    // 2. Find user's access to the requested destination

    Optional<DestinationAccess> destinationAccess =
        user.getDestinationAccesses().stream()
            .filter(access -> access.getDestination().equals(request.getDestination()))
            .findFirst();

    if (destinationAccess.isEmpty()) {
      return new AccessCheckResponse(false, "No access to destination");
    }

    // 3. Convert requested action
    Action action;

    try {
      action = Action.valueOf(request.getAction().toUpperCase());
    } catch (IllegalArgumentException e) {
      return new AccessCheckResponse(false, "Invalid action");
    }

    // 4. Check permission
    boolean authorised = destinationAccess.get().getPermission().allows(action);

    if (authorised) {
      return new AccessCheckResponse(true, "Access granted");
    }

    return new AccessCheckResponse(false, "Insufficient permission");
  }
}

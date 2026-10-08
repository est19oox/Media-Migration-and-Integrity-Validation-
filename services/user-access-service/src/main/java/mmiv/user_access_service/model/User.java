package mmiv.user_access_service.model;

import java.util.List;

public class User {

    private final String userId;
    private final List<DestinationAccess> destinationAccesses;

    public User(
            String userId,
            List<DestinationAccess> destinationAccesses) {

        this.userId = userId;
        this.destinationAccesses = destinationAccesses;
    }

    public String getUserId() {
        return userId;
    }

    public List<DestinationAccess> getDestinationAccesses() {
        return destinationAccesses;
    }
}
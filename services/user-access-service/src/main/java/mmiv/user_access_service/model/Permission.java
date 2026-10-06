package mmiv.user_access_service.model;

public enum Permission {
    READ,
    WRITE;

    public boolean allows(Action action) {
        return switch (this) {
            case READ ->
                action == Action.READ;

            case WRITE ->
                action == Action.READ
                        || action == Action.WRITE;
        };
    }
}
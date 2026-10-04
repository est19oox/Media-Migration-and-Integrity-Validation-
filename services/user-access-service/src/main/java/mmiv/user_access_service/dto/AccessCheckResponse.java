package mmiv.user_access_service.dto;

public class AccessCheckResponse {

    private boolean authorised;
    private String reason;

    public AccessCheckResponse(boolean authorised, String reason) {
        this.authorised = authorised;
        this.reason = reason;
    }

    public boolean isAuthorised() {
        return authorised;
    }

    public String getReason() {
        return reason;
    }
}
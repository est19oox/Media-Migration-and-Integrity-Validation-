package mmiv.user_access_service.dto;

import jakarta.validation.constraints.NotBlank;

public class AccessCheckRequest {
    
    @NotBlank 
    private String userId;

    @NotBlank 
    private String action;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }
}
package mmiv.user_access_service.dto;

public record UserResponse(String userId, String username, String role, boolean active) {}

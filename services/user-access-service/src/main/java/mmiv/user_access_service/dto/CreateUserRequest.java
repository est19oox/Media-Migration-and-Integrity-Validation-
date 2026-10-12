package mmiv.user_access_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotBlank @Size(max = 100) String username, @NotBlank String password, @NotBlank String role) {}

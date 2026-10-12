package mmiv.user_access_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,40}") String username,
    @NotBlank @Size(min = 8, max = 72) String password) {}

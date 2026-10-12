package mmiv.user_access_service.dto;

import jakarta.validation.constraints.NotNull;

public record SetUserActiveRequest(@NotNull Boolean active) {}

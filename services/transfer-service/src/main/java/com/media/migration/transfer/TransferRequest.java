package com.media.migration.transfer;

import jakarta.validation.constraints.NotBlank;

public record TransferRequest(
        @NotBlank String sourcePath,
        @NotBlank String destinationPath
) {}

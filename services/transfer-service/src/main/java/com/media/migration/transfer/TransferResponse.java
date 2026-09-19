package com.media.migration.transfer;

public record TransferResponse(
        String sourcePath,
        String destinationPath,
        long bytesTransferred,
        String sourceSha256,
        String destinationSha256,
        boolean integrityValid
) {}

package mmiv.transfer.dto;

import java.nio.file.Path;

/** Internal, server-created source. Never bind a client request to this record. */
public record UploadSource(Path path, String fileName, String key,
                           String contentType, long sizeBytes, String sha256) {}

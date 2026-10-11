package mmiv.transfer.logging;

import mmiv.transfer.dto.TransferResponse;
import mmiv.transfer.dto.UploadResponse;
import mmiv.transfer.files.StoredFile;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

@Component
public class FileEventLogger {
    private final SqsLogOutbox outbox;
    public FileEventLogger(SqsLogOutbox outbox) { this.outbox = outbox; }

    public void upload(UploadResponse result, Instant startedAt) {
        emit("UPLOAD", result.transferId(), result.fileId(), result.fileName(), result.bucket(),
                result.key(), result.sizeBytes(), null, result.sha256(), result.validationResult(),
                result.cleanupStatus(), result.success(), null, startedAt);
    }

    public void transfer(TransferResponse result, Instant startedAt) {
        String key = result.key();
        emit("TRANSFER", result.transferId(), result.fileId(), key.substring(key.lastIndexOf('/') + 1),
                result.bucket(), key, result.sizeBytes(), null, result.sha256(), result.validationResult(),
                result.cleanupStatus(), result.success(), null, startedAt);
    }

    public void download(String operation, String fileId, StoredFile file, long bytesServed,
            boolean success, String errorType, Instant startedAt) {
        emit(operation, null, fileId, file == null ? null : file.fileName(),
                file == null ? null : file.bucket(), file == null ? null : file.key(),
                file == null ? null : file.sizeBytes(), bytesServed, null, null, null,
                success, errorType, startedAt);
    }

    public void failure(String operation, String fileName, String key, RuntimeException error, Instant startedAt) {
        emit(operation, null, null, fileName, null, key, null, null, null, null, null,
                false, error.getClass().getSimpleName(), startedAt);
    }

    private void emit(String operation, String transferId, String fileId, String fileName,
            String bucket, String key, Long sizeBytes, Long bytesServed, String sha256,
            String validationResult, String cleanupStatus, boolean success,
            String errorType, Instant startedAt) {
        outbox.enqueue(new FileLogEvent(1, UUID.randomUUID().toString(), operation, transferId,
                fileId, fileName, bucket, key, sizeBytes, bytesServed, sha256, validationResult,
                cleanupStatus, success, errorType, startedAt.toString(), Instant.now().toString()));
    }
}

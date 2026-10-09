package mmiv.transfer.service;

import mmiv.transfer.dto.S3ObjectReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class S3ObjectCleanup {
    private final S3Client s3;
    private final int maxAttempts;
    private final long retryDelayMs;
    public S3ObjectCleanup(S3Client s3,
            @Value("${mmiv.cleanup.max-attempts:3}") int maxAttempts,
            @Value("${mmiv.cleanup.retry-delay-ms:1000}") long retryDelayMs) {
        if (maxAttempts < 1 || maxAttempts > 10 || retryDelayMs < 0 || retryDelayMs > 5000) {
            throw new IllegalArgumentException("Cleanup attempts must be 1–10 and delay 0–5000 ms");
        }
        this.s3 = s3;
        this.maxAttempts = maxAttempts;
        this.retryDelayMs = retryDelayMs;
    }
    public record Result(String status, String message) {}
    public Result delete(String bucket, String key, S3ObjectReference reference) {
        if (reference == null || reference.eTag() == null || reference.eTag().isBlank()
                || "*".equals(reference.eTag())) return failed();
        var builder = DeleteObjectRequest.builder().bucket(bucket).key(key).ifMatch(reference.eTag());
        if (reference.versionId() != null && !reference.versionId().isBlank()) {
            // Explicit version deletion removes these uploaded bytes, rather than adding a delete marker.
            builder.versionId(reference.versionId());
        }
        var request = builder.build();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                s3.deleteObject(request);
                return new Result("DELETED", "Uploaded file deleted after unsuccessful integrity validation");
            } catch (S3Exception e) {
                if (e.statusCode() == 404) return new Result("DELETED", "Uploaded file is already absent");
                if (e.statusCode() == 412 || e.statusCode() == 409) {
                    return new Result("DELETE_FAILED", "Cleanup stopped because the object changed or conflicted");
                }
                // Permissions and other client errors will not be fixed by repeating the same request.
                if (e.statusCode() >= 400 && e.statusCode() < 500
                        && e.statusCode() != 429 && e.statusCode() != 408) return failed();
            } catch (SdkException e) {
                // Retry a network or transient SDK failure, using the same object identity.
            }
            if (attempt < maxAttempts) {
                try { Thread.sleep(retryDelayMs); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return failed();
                }
            }
        }
        return failed();
    }
    private static Result failed() {
        return new Result("DELETE_FAILED", "Automatic deletion could not be confirmed; manual cleanup is required");
    }
}

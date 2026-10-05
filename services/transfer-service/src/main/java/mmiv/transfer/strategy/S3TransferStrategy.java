package mmiv.transfer.strategy;

import mmiv.transfer.dto.TransferRequest;
import mmiv.transfer.dto.TransferResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Component
public class S3TransferStrategy implements TransferStrategy {
    private static final Set<String> SUPPORTED = Set.of("png", "jpg", "jpeg", "mp4", "txt", "pdf");

    private final S3Client s3Client;
    private final String bucket;

    public S3TransferStrategy(S3Client s3Client,
                              @Value("${mmiv.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public boolean supports(TransferRequest request) {
        String name = Path.of(request.sourcePath()).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && SUPPORTED.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    @Override
    public TransferResponse transfer(TransferRequest request) {
        try {
            Path source = Path.of(request.sourcePath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) {
                throw new IllegalArgumentException("Source file does not exist: " + source);
            }

            String contentType = request.contentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = Files.probeContentType(source);
            }
            if (contentType == null) contentType = "application/octet-stream";

            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(request.destinationKey())
                    .contentType(contentType)
                    .build();

            s3Client.putObject(put, RequestBody.fromFile(source));
            return new TransferResponse(bucket, request.destinationKey(), contentType, Files.size(source));
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalStateException("S3 transfer failed", e);
        }
    }
}

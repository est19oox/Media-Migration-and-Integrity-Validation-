package mmiv.transfer.strategy;

import mmiv.transfer.dto.TransferRequest;
import mmiv.transfer.validation.TransferRequestValidator;
import mmiv.transfer.dto.TransferResponse;
import mmiv.transfer.dto.UploadSource;
import mmiv.transfer.dto.UploadResponse;
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
    private static final Set<String> SUPPORTED = Set.of("png", "jpg", "jpeg", "mp4", "txt", "pdf", "ppt", "pptx", "doc", "docx");

    private final S3Client s3Client;
    private final String bucket;
    private final TransferRequestValidator validator;

    public S3TransferStrategy(S3Client s3Client,
                              @Value("${mmiv.s3.bucket}") String bucket, TransferRequestValidator validator) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.validator = validator;
    }

    private static mmiv.transfer.dto.S3ObjectReference reference(
            software.amazon.awssdk.services.s3.model.PutObjectResponse stored) {
        return stored == null ? null : new mmiv.transfer.dto.S3ObjectReference(stored.eTag(), stored.versionId());
    }

    @Override
    public boolean supports(TransferRequest request) {
        String name = Path.of(request.sourcePath()).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && SUPPORTED.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    @Override
    public TransferResponse transfer(TransferRequest request) {
        Path temporary = null;
        try {
            Path source = validator.resolveSource(request.sourcePath());
            if (!Files.isRegularFile(source)) {
                throw new IllegalArgumentException("Source file does not exist: " + source);
            }

            String contentType = request.contentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = validator.uploadContentType(source.getFileName().toString());
            }
            if (contentType == null) contentType = "application/octet-stream";

            temporary = Files.createTempFile("mmiv-transfer-", ".tmp");
            Files.copy(source, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = new java.security.DigestInputStream(Files.newInputStream(temporary), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            byte[] checksum = digest.digest();
            String sha256 = java.util.HexFormat.of().formatHex(checksum);
            PutObjectRequest put = PutObjectRequest.builder()
                    .checksumSHA256(java.util.Base64.getEncoder().encodeToString(checksum))
                    .bucket(bucket)
                    .key(request.destinationKey())
                    .contentType(contentType)
                    .build();

            var stored = s3Client.putObject(put, RequestBody.fromFile(temporary));
            return new TransferResponse(bucket, request.destinationKey(), contentType, Files.size(temporary),
                    mmiv.transfer.files.FileIds.encode(request.destinationKey()), sha256, null,
                    "NOT_REQUESTED", false, "Uploaded; integrity has not been checked",
                    reference(stored), "NOT_ATTEMPTED");
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalStateException("S3 transfer failed", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (java.io.IOException e) {
                    org.slf4j.LoggerFactory.getLogger(S3TransferStrategy.class).warn("Could not delete transfer temporary file");
                }
            }
        }
    }

    @Override
    public UploadResponse transferUploaded(UploadSource source) {
        try {
            // The source path was created by the server, not supplied by the user.
            byte[] digest = java.util.HexFormat.of().parseHex(source.sha256());
            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(bucket).key(source.key()).contentType(source.contentType())
                    .checksumSHA256(java.util.Base64.getEncoder().encodeToString(digest))
                    .build();
            var stored = s3Client.putObject(put, RequestBody.fromFile(source.path()));
            return new UploadResponse(bucket, source.key(), source.fileName(),
                    source.contentType(), source.sizeBytes(), source.sha256(),
                    mmiv.transfer.files.FileIds.encode(source.key()), null, "NOT_REQUESTED", false,
                    "Uploaded; integrity has not been checked", reference(stored), "NOT_ATTEMPTED");
        } catch (Exception e) {
            throw new IllegalStateException("S3 upload failed", e);
        }
    }
}

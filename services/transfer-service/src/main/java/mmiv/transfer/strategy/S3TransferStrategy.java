package mmiv.transfer.strategy;

import java.util.Base64;
import java.util.HexFormat;
import mmiv.transfer.dto.PreparedTransfer;
import mmiv.transfer.dto.S3ObjectReference;
import mmiv.transfer.dto.StoredTransfer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
public class S3TransferStrategy implements TransferStrategy {
    private final S3Client s3Client;
    private final String bucket;

    public S3TransferStrategy(S3Client s3Client, @Value("${mmiv.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public TransferBackend backend() {
        return TransferBackend.S3;
    }

    @Override
    public StoredTransfer transfer(PreparedTransfer source) {
        try {
            var put = PutObjectRequest.builder()
                    .bucket(bucket).key(source.key()).contentType(source.contentType())
                    .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(source.sha256())))
                    .build();
            var stored = s3Client.putObject(put, RequestBody.fromFile(source.path()));
            var reference = stored == null ? null : new S3ObjectReference(stored.eTag(), stored.versionId());
            return new StoredTransfer(bucket, source.key(), reference);
        } catch (RuntimeException error) {
            throw new IllegalStateException("S3 transfer failed", error);
        }
    }
}

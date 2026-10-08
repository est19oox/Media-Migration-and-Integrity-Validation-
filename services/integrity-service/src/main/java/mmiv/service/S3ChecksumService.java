package mmiv.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class S3ChecksumService {

    private final S3Client s3Client;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "S3Client is intentionally injected and managed by Spring."
    )
    public S3ChecksumService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public String calculateSHA256(
            String bucket,
            String objectKey) throws IOException {

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        try (
                ResponseInputStream<GetObjectResponse> inputStream =
                        s3Client.getObject(request)
        ) {

            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] buffer = new byte[8192];

            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }

            return HexFormat.of()
                    .formatHex(digest.digest());

        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    e
            );
        }
    }
}
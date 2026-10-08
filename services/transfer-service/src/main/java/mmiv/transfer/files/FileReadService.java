package mmiv.transfer.files;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Service
public class FileReadService {
    private final S3Client s3;
    private final String bucket;
    public FileReadService(S3Client s3, @Value("${mmiv.s3.bucket}") String bucket) {
        this.s3 = s3; this.bucket = bucket;
    }
    public StoredFile describe(String fileId) {
        String key = FileIds.decode(fileId);
        // Future: enforce the authenticated user's ownership/permission before accessing S3.
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            String name = key.substring(key.lastIndexOf('/') + 1);
            // Keep response filenames safe even for legacy keys.
            name = name.replaceAll("[\\p{Cntrl}\\\\\";]", "_");
            if (name.isBlank()) name = "download";
            return new StoredFile(bucket, key, name, head.contentLength(), head.eTag());
        } catch (S3Exception e) { throw translate(e); }
        catch (SdkClientException e) { throw unavailable(); }
    }
    public ResponseInputStream<GetObjectResponse> open(StoredFile file, ByteRange range) {
        try {
            GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(file.key());
            // Prevent metadata from one version being paired with content from another.
            if (file.eTag() != null) request.ifMatch(file.eTag());
            if (range != null) request.range(range.s3Header());
            return s3.getObject(request.build());
        } catch (S3Exception e) { throw translate(e); }
        catch (SdkClientException e) { throw unavailable(); }
    }
    private FileStorageException translate(S3Exception e) {
        return switch (e.statusCode()) {
            case 404 -> new FileStorageException(404, "File not found");
            case 403 -> new FileStorageException(403, "Storage denied file access");
            case 412 -> new FileStorageException(412, "File changed; retry the request");
            default -> unavailable();
        };
    }
    private FileStorageException unavailable() { return new FileStorageException(502, "Cloud file could not be read"); }
}

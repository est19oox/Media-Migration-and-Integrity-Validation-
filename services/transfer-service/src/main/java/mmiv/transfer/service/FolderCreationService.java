package mmiv.transfer.service;

import mmiv.transfer.domain.StoredFolder;
import mmiv.transfer.integration.FolderAccessGuard;
import mmiv.transfer.integration.PortalClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Coordinates storage and account boundaries; folder naming belongs to the domain. */
@Service
public class FolderCreationService {
  private final PortalClient access;
  private final S3Client storage;
  private final String bucket;
  private final FolderAccessGuard guard;

  public FolderCreationService(
      PortalClient access,
      S3Client storage,
      @Value("${mmiv.s3.bucket}") String bucket,
      FolderAccessGuard guard) {
    this.access = access;
    this.storage = storage;
    this.bucket = bucket;
    this.guard = guard;
  }

  public Object create(String name) {
    access.admin();
    StoredFolder folder;
    try {
      folder = StoredFolder.forCreation(name, bucket);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }
    try (var scope = guard.write(folder.name())) {
      try {
        storage.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(folder.prefix())
                .ifNoneMatch("*")
                .contentType("application/x-directory")
                .build(),
            software.amazon.awssdk.core.sync.RequestBody.empty());
      } catch (S3Exception error) {
        if (error.statusCode() != 412)
          throw new ResponseStatusException(
              HttpStatus.BAD_GATEWAY, "Folder could not be created in storage");
      }
      return access.access(
          HttpMethod.POST, "/api/admin/folders", java.util.Map.of("name", folder.name()));
    }
  }
}

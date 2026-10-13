package mmiv.transfer.service;

import java.util.UUID;
import mmiv.transfer.integration.*;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Service
public class VerificationCleanupService {
  private final MetadataClient metadata;
  private final VerificationRunGuard runs;
  private final S3Client s3;
  @org.springframework.beans.factory.annotation.Autowired private FolderAccessGuard folders;
  @org.springframework.beans.factory.annotation.Autowired private StorageDeletionService storage;

  @org.springframework.beans.factory.annotation.Value("${mmiv.s3.bucket}")
  private String bucket;

  public VerificationCleanupService(
      MetadataClient metadata, VerificationRunGuard runs, S3Client s3) {
    this.metadata = metadata;
    this.runs = runs;
    this.s3 = s3;
  }

  public Object finish(UUID run) {
    var folder = metadata.verificationFolder(run);
    if (folder == null || !folder.owned()) return java.util.Map.of("removedFolder", false);
    if (!("mmiv-verification-" + run).equals(folder.name()) || !metadata.verificationClosed(run))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "Verification run must be closed first");
    try (var scope = folders.write(folder.name())) {
      String marker = folder.name() + "/";
      for (var page :
          s3.listObjectVersionsPaginator(
              ListObjectVersionsRequest.builder().bucket(bucket).prefix(marker).build()))
        if (page.versions().stream().anyMatch(o -> !marker.equals(o.key()))
            || page.deleteMarkers().stream().anyMatch(o -> !marker.equals(o.key())))
          throw new org.springframework.web.server.ResponseStatusException(
              org.springframework.http.HttpStatus.CONFLICT,
              "Verification folder still contains files; retry cleanup");
      storage.delete(bucket, marker, marker::equals);
      metadata.removeVerificationFolder(run);
    }
    return java.util.Map.of("removedFolder", true);
  }

  public Object cleanup(UUID run) {
    var plans =
        metadata.verificationPlans(run); // Validate every assignment before the first delete.
    runs.cleanup(
        MetadataClient.currentJwt().getSubject(),
        run,
        () -> {
          for (var plan : plans) {
            var request =
                ListObjectVersionsRequest.builder()
                    .bucket(plan.bucket())
                    .prefix(plan.destinationKey())
                    .build();
            var deletes = new java.util.ArrayList<DeleteObjectRequest>();
            for (var page : s3.listObjectVersionsPaginator(request)) {
              for (var version : page.versions())
                if (version.key().equals(plan.destinationKey()))
                  deletes.add(
                      DeleteObjectRequest.builder()
                          .bucket(plan.bucket())
                          .key(version.key())
                          .versionId(version.versionId())
                          .build());
              for (var marker : page.deleteMarkers())
                if (marker.key().equals(plan.destinationKey()))
                  deletes.add(
                      DeleteObjectRequest.builder()
                          .bucket(plan.bucket())
                          .key(marker.key())
                          .versionId(marker.versionId())
                          .build());
            }
            // Finish listing before deletion so pagination markers cannot be invalidated.
            for (var delete : deletes) s3.deleteObject(delete);
          }
        });
    return java.util.Map.of("cleanedPlans", plans.size());
  }
}

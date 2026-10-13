package mmiv.transfer.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectVersionsIterable;

class StorageDeletionServiceTest {
  @Test
  void removesAllExactVersionsAndDeleteMarkersButKeepsPrefixNeighbors() {
    var s3 = mock(S3Client.class);
    when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
        .thenReturn(
            ListObjectVersionsResponse.builder()
                .isTruncated(false)
                .versions(
                    ObjectVersion.builder().key("f/a").versionId("v1").build(),
                    ObjectVersion.builder().key("f/a.bak").versionId("v2").build())
                .deleteMarkers(DeleteMarkerEntry.builder().key("f/a").versionId("marker").build())
                .build());
    when(s3.listObjectVersionsPaginator(any(ListObjectVersionsRequest.class)))
        .thenAnswer(a -> new ListObjectVersionsIterable(s3, a.getArgument(0)));
    when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenReturn(DeleteObjectsResponse.builder().build());
    assertEquals(2, new StorageDeletionService(s3).delete("bucket", "f/a", "f/a"::equals));
    verify(s3)
        .deleteObjects(
            argThat(
                (DeleteObjectsRequest req) ->
                    req.delete().objects().size() == 2
                        && req.delete().objects().stream().allMatch(o -> o.key().equals("f/a"))));
  }

  @Test
  void partialStorageFailureIsReported() {
    var s3 = mock(S3Client.class);
    when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
        .thenReturn(
            ListObjectVersionsResponse.builder()
                .isTruncated(false)
                .versions(ObjectVersion.builder().key("a").versionId("null").build())
                .build());
    when(s3.listObjectVersionsPaginator(any(ListObjectVersionsRequest.class)))
        .thenAnswer(a -> new ListObjectVersionsIterable(s3, a.getArgument(0)));
    when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenReturn(
            DeleteObjectsResponse.builder()
                .errors(S3Error.builder().key("a").code("AccessDenied").build())
                .build());
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> new StorageDeletionService(s3).delete("bucket", "a", k -> true));
  }
}

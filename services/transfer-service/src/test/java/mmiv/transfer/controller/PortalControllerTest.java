package mmiv.transfer.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import mmiv.transfer.files.FileIds;
import mmiv.transfer.integration.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class PortalControllerTest {
  final MetadataClient metadata = mock(MetadataClient.class);
  final PortalClient portal = mock(PortalClient.class);
  final S3Client s3 = mock(S3Client.class);
  final PortalController controller =
      new PortalController(
          metadata,
          portal,
          s3,
          "bucket",
          mock(mmiv.transfer.service.FileMutationService.class),
          new mmiv.transfer.service.FolderCreationService(
              portal, s3, "bucket", new FolderAccessGuard()));

  @Test
  void profileHistoryAndLogsPortalMethodsNoLongerExist() {
    var methods =
        java.util.Arrays.stream(PortalController.class.getDeclaredMethods())
            .map(java.lang.reflect.Method::getName)
            .collect(java.util.stream.Collectors.toSet());
    assertFalse(methods.contains("me"));
    assertFalse(methods.contains("history"));
    assertFalse(methods.contains("logs"));
  }

  @Test
  void filesFoldersAndAdminMutationsRemainMapped() throws Exception {
    assertNotNull(PortalController.class.getDeclaredMethod("files", String.class, String.class));
    assertNotNull(
        PortalController.class.getDeclaredMethod("create", PortalController.Folder.class));
    assertTrue(
        java.util.Arrays.stream(PortalController.class.getDeclaredMethods())
            .noneMatch(
                method ->
                    method.getName().equals("grant") || method.getName().equals("deleteUser")));
  }

  @Test
  void ordinaryUsersCannotCreateStorageMarkers() {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(portal).admin();
    assertThrows(
        ResponseStatusException.class,
        () -> controller.create(new PortalController.Folder("SW001")));
    verifyNoInteractions(s3);
    verify(portal, never()).access(any(), anyString(), any());
  }

  @Test
  void folderNamesCannotEscapeTheAssignedPrefix() {
    for (String name :
        new String[] {
          "../other", "SW001/semester2", "bucket", "migrations", "upload", "SW001\\other"
        })
      assertThrows(
          ResponseStatusException.class,
          () -> controller.create(new PortalController.Folder(name)));
    verifyNoInteractions(s3);
  }

  @Test
  void deniedFolderListingNeverContactsStorage() {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
        .when(metadata)
        .requireFolder("semester2", "READ");
    assertThrows(ResponseStatusException.class, () -> controller.files("semester2", null));
    verifyNoInteractions(s3);
  }

  @Test
  void listingKeepsExactFolderBoundaryAndHidesMigrationStoragePaths() {
    when(metadata.profile())
        .thenReturn(new MetadataClient.Profile("id", "admin", "ADMIN", java.util.List.of()));
    String key = "SW001/migrations/11111111-1111-4111-8111-111111111111/docs/report.pdf";
    when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenReturn(
            ListObjectsV2Response.builder()
                .contents(
                    S3Object.builder().key("SW001/").size(0L).build(),
                    S3Object.builder().key(key).size(4L).build())
                .nextContinuationToken("next-page")
                .build());
    var result = (java.util.Map<?, ?>) controller.files("SW001", "previous-page");
    verify(metadata).requireFolder("SW001", "READ");
    verify(s3)
        .listObjectsV2(
            argThat(
                (ListObjectsV2Request r) ->
                    r.prefix().equals("SW001/")
                        && r.bucket().equals("bucket")
                        && r.continuationToken().equals("previous-page")));
    var files = (java.util.List<?>) result.get("files");
    assertEquals(1, files.size());
    var file = (PortalController.FileEntry) files.getFirst();
    assertEquals("docs/report.pdf", file.name());
    assertEquals(key, FileIds.decode(file.fileId()));
    assertEquals("next-page", result.get("nextCursor"));
  }
}

package mmiv.transfer.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import mmiv.transfer.files.FileIds;
import mmiv.transfer.integration.*;
import mmiv.transfer.logging.FileEventLogger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FileMutationServiceTest {
  final MetadataClient metadata = mock(MetadataClient.class);
  final PortalClient portal = mock(PortalClient.class);
  final StorageDeletionService storage = mock(StorageDeletionService.class);
  final FileMutationService service =
      new FileMutationService(
          metadata,
          portal,
          new FolderAccessGuard(),
          storage,
          "bucket",
          mock(FileEventLogger.class));
  final UUID id = UUID.randomUUID();
  final String key = "SW001/migrations/" + id + "/docs/report.pdf";

  void profile(String role) {
    when(metadata.profile())
        .thenReturn(new MetadataClient.Profile("owner", "alice", role, List.of()));
  }

  @Test
  void otherUsersFileCannotBeDeleted() {
    profile("USER");
    when(metadata.resolveDelete(id)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
    assertThrows(ResponseStatusException.class, () -> service.deleteFile(FileIds.encode(key)));
    verifyNoInteractions(storage);
  }

  @Test
  void revokedWriteCannotDeleteEvenOwnedFile() {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
        .when(metadata)
        .requireFolder("SW001", "WRITE");
    assertThrows(ResponseStatusException.class, () -> service.deleteFile(FileIds.encode(key)));
    verifyNoInteractions(storage);
  }

  @Test
  void ownPlanMustMatchTheExactObject() {
    profile("USER");
    when(metadata.resolveDelete(id)).thenReturn(plan(key + "-different"));
    assertThrows(ResponseStatusException.class, () -> service.deleteFile(FileIds.encode(key)));
    verifyNoInteractions(storage);
  }

  @Test
  void uploaderCanDeleteOnlyExactKeyIncludingVersions() {
    profile("USER");
    when(metadata.resolveDelete(id)).thenReturn(plan(key));
    service.deleteFile(FileIds.encode(key));
    verify(storage)
        .delete(eq("bucket"), eq(key), argThat(p -> p.test(key) && !p.test(key + ".bak")));
    verify(metadata).deletePlan(id, key);
  }

  @Test
  void adminCanDeleteOthersFile() {
    profile("ADMIN");
    service.deleteFile(FileIds.encode(key));
    verify(metadata, never()).resolveDelete(any());
    verify(storage).delete(eq("bucket"), eq(key), any());
  }

  @Test
  void ordinaryUsersCannotDeleteFolders() {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(portal).admin();
    assertThrows(ResponseStatusException.class, () -> service.deleteFolder("SW001", "docs"));
    verifyNoInteractions(storage);
  }

  @Test
  void nestedDeletionKeepsSiblingPathsAndRegisteredFolder() {
    service.deleteFolder("SW001", "docs");
    verify(storage)
        .delete(
            eq("bucket"),
            eq("SW001/"),
            argThat(
                p ->
                    p.test(key)
                        && !p.test(key.replace("/docs/", "/docs-other/"))
                        && !p.test(key.replace("SW001/", "SW001-other/"))));
    verify(metadata).deleteFolderPlans("SW001", "docs");
    verify(portal, never()).access(any(), any(), any());
  }

  MetadataClient.Plan plan(String object) {
    return new MetadataClient.Plan(
        id,
        id,
        UUID.randomUUID(),
        "owner",
        "SW001",
        "bucket",
        object,
        null,
        "report.pdf",
        "application/pdf",
        4,
        "UPLOAD",
        "docs/report.pdf");
  }
}

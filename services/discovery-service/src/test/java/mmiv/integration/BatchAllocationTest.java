package mmiv.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mmiv.events.EventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class BatchAllocationTest {
  @TempDir Path root;
  private final PermissionClient permissions = mock(PermissionClient.class);
  private final MetadataPlanRepository plans = mock(MetadataPlanRepository.class);
  private final EventPublisher events = mock(EventPublisher.class);
  private final Jwt jwt =
      Jwt.withTokenValue("caller-token").header("alg", "RS256").subject("caller").build();

  private MetadataService service() {
    when(plans.saveNamedBatch(anyList(), anyCollection()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    return new MetadataService(permissions, plans, events, root.toString(), "landing", "bucket");
  }

  @Test
  void allocatesOrderedFilesUnderOneMigrationAndPublishesOneRunLifecycle() {
    BatchAllocationResponse response = service().allocateBatch(jwt, request(2), null);

    assertEquals(2, response.plans().size());
    assertTrue(
        response.plans().stream().allMatch(p -> p.migrationId().equals(response.migrationId())));
    assertEquals(2, response.plans().stream().map(MetadataPlan::metadataId).distinct().count());
    assertEquals(2, response.plans().stream().map(MetadataPlan::fileId).distinct().count());
    assertEquals("folder/file-0.txt", response.plans().get(0).objectPath());
    assertEquals("folder/file-1.txt", response.plans().get(1).objectPath());
    response
        .plans()
        .forEach(
            p -> assertTrue(p.destinationKey().contains("/migrations/" + p.metadataId() + "/")));

    verify(events, times(1))
        .migration(
            eq("MIGRATION_STARTED"), anyString(), eq(response.migrationId()), isNull(), anyMap());
    verify(events, times(2))
        .migration(
            eq("FILE_DISCOVERY_COMPLETED"),
            anyString(),
            eq(response.migrationId()),
            any(),
            anyMap());
    verify(events, times(1))
        .migration(
            eq("MIGRATION_DISCOVERY_COMPLETED"),
            anyString(),
            eq(response.migrationId()),
            isNull(),
            argThat(data -> Integer.valueOf(2).equals(data.get("totalFiles"))));
  }

  @Test
  void invalidSecondFilePersistsAndPublishesNothing() {
    var request =
        new BatchAllocationRequest(
            "landing",
            List.of(),
            List.of(
                new BatchAllocationRequest.FileItem("valid.txt", 5L, "valid.txt"),
                new BatchAllocationRequest.FileItem("bad.exe", 5L, "bad.exe")));

    assertThrows(ResponseStatusException.class, () -> service().allocateBatch(jwt, request, null));

    verifyNoInteractions(plans, events);
  }

  @Test
  void acceptsMaximumBatchAndDuplicateBasenamesInDifferentPaths() {
    var files = new ArrayList<BatchAllocationRequest.FileItem>();
    files.add(new BatchAllocationRequest.FileItem("same.txt", 5L, "one/same.txt"));
    files.add(new BatchAllocationRequest.FileItem("same.txt", 5L, "two/same.txt"));
    for (int i = 2; i < 500; i++) {
      files.add(
          new BatchAllocationRequest.FileItem("file-" + i + ".txt", 5L, "file-" + i + ".txt"));
    }

    BatchAllocationResponse response =
        service().allocateBatch(jwt, new BatchAllocationRequest("landing", List.of(), files), null);

    assertEquals(500, response.plans().size());
    assertEquals("one/same.txt", response.plans().get(0).objectPath());
    assertEquals("two/same.txt", response.plans().get(1).objectPath());
  }

  @Test
  void rejectsOversizedBatchAndUnauthorizedOrMismatchedVerificationRun() {
    var tooMany = new ArrayList<BatchAllocationRequest.FileItem>();
    for (int i = 0; i < 501; i++) {
      tooMany.add(
          new BatchAllocationRequest.FileItem("file-" + i + ".txt", 5L, "file-" + i + ".txt"));
    }
    assertThrows(
        ResponseStatusException.class,
        () ->
            service()
                .allocateBatch(
                    jwt, new BatchAllocationRequest("landing", List.of(), tooMany), null));

    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
        .when(permissions)
        .require("caller-token", "caller", "landing", "WRITE");
    assertThrows(
        ResponseStatusException.class, () -> service().allocateBatch(jwt, request(1), null));

    UUID run = UUID.randomUUID();
    assertThrows(
        ResponseStatusException.class,
        () ->
            service()
                .allocateBatch(
                    jwt,
                    new BatchAllocationRequest(
                        "mmiv-verification-" + UUID.randomUUID(), List.of(), request(1).files()),
                    run));
  }

  private BatchAllocationRequest request(int count) {
    var files = new ArrayList<BatchAllocationRequest.FileItem>();
    for (int i = 0; i < count; i++) {
      files.add(
          new BatchAllocationRequest.FileItem(
              "file-" + i + ".txt", 5L, "folder/file-" + i + ".txt"));
    }
    return new BatchAllocationRequest("landing", List.of(), files);
  }
}

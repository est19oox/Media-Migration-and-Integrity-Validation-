package mmiv.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import mmiv.events.EventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

class BatchAllocationTest {
  @TempDir Path root;
  private final PermissionClient permissions = mock(PermissionClient.class);
  private final MetadataPlanRepository plans = mock(MetadataPlanRepository.class);
  private final MetadataAssignmentService assignments = mock(MetadataAssignmentService.class);
  private final mmiv.repository.VerificationRunRepository runs =
      mock(mmiv.repository.VerificationRunRepository.class);
  private final EventPublisher events = mock(EventPublisher.class);
  private final Jwt jwt =
      Jwt.withTokenValue("caller-token").header("alg", "RS256").subject("caller").build();

  private MetadataService service() {
    when(assignments.allocateBatch(anyList(), anyCollection()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    return new MetadataService(
        permissions, plans, assignments, runs, events, root.toString(), "landing", "bucket");
  }

  @Test
  void allocatesAllFolderFilesUnderOneMigration() {
    BatchAllocationResponse response = service().allocateBatch(jwt, request(2), null);

    assertEquals(2, response.plans().size());
    assertTrue(
        response.plans().stream().allMatch(p -> response.migrationId().equals(p.migrationId())));
    assertEquals(2, response.plans().stream().map(p -> p.metadataId()).distinct().count());
    assertEquals(2, response.plans().stream().map(p -> p.fileId()).distinct().count());
    assertEquals("folder/file-0.txt", response.plans().get(0).objectPath());
    assertEquals("folder/file-1.txt", response.plans().get(1).objectPath());

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
  void invalidFileRejectsTheWholeBatchBeforePersistenceOrEvents() {
    var invalid =
        new BatchAllocationRequest(
            "landing",
            List.of(),
            List.of(
                new BatchAllocationRequest.FileItem("valid.txt", 5L, "valid.txt"),
                new BatchAllocationRequest.FileItem("bad.exe", 5L, "bad.exe")));

    assertThrows(ResponseStatusException.class, () -> service().allocateBatch(jwt, invalid, null));

    verifyNoInteractions(assignments, events);
  }

  @Test
  void allocationAndDurableEventEnqueueShareOneTransactionBoundary() throws Exception {
    assertNotNull(
        MetadataService.class
            .getMethod(
                "allocateBatch", Jwt.class, BatchAllocationRequest.class, java.util.UUID.class)
            .getAnnotation(Transactional.class));

    doThrow(new IllegalStateException("outbox unavailable"))
        .when(events)
        .migration(eq("MIGRATION_STARTED"), anyString(), any(), isNull(), anyMap());
    assertThrows(IllegalStateException.class, () -> service().allocateBatch(jwt, request(2), null));
    verify(assignments).allocateBatch(anyList(), anyCollection());
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

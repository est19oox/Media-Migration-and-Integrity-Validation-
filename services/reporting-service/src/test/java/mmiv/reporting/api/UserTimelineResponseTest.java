package mmiv.reporting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mmiv.reporting.audit.AuditEventDocument;
import mmiv.reporting.audit.AuditEventRepository;
import mmiv.reporting.contract.EventType;
import mmiv.reporting.report.MigrationReportDocument;
import mmiv.reporting.report.MigrationReportRepository;
import mmiv.reporting.report.MigrationSource;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

class UserTimelineResponseTest {
  private static final UUID MIGRATION_ID = UUID.fromString("c49f2f8f-ad97-45a8-8867-f3ca8a971d80");

  @Test
  void jsonContainsOnlyAllowlistedPresentationFields() throws Exception {
    AuditEventDocument event =
        event(
            EventType.INTEGRITY_VALIDATION_COMPLETED,
            "file-1",
            "2026-10-11T10:02:00Z",
            new Document("status", "FAILED")
                .append("sourceChecksum", "secret-source")
                .append("targetChecksum", "secret-target")
                .append("failure", new Document("message", "internal stack trace"))
                .append("internalAddress", "rabbitmq.internal:5672"));

    String json = JsonMapper.builder().build().writeValueAsString(UserTimelineResponse.from(event));

    assertTrue(json.contains("\"stage\":\"INTEGRITY\""));
    assertTrue(json.contains("\"status\":\"FAILED\""));
    assertTrue(json.contains("\"message\":\"File integrity validation completed\""));
    for (String forbidden :
        new String[] {
          "data",
          "Checksum",
          "secret-source",
          "secret-target",
          "stack trace",
          "internalAddress",
          "eventId",
          "schemaVersion"
        }) {
      assertFalse(json.contains(forbidden), forbidden);
    }
  }

  @Test
  void unknownStatusIsNormalizedInsteadOfCopied() {
    UserTimelineResponse timeline =
        UserTimelineResponse.from(
            event(
                EventType.FILE_TRANSFER_COMPLETED,
                "file-1",
                "2026-10-11T10:02:00Z",
                new Document("status", "INTERNAL_RETRY_AT_10.0.0.4")));

    assertEquals("UNKNOWN", timeline.status());
    assertEquals("File transfer completed", timeline.message());
  }

  @Test
  void timelineUsesAscendingTimeAndDeterministicEventTypeTieBreak() {
    MigrationReportRepository reports = mock(MigrationReportRepository.class);
    AuditEventRepository events = mock(AuditEventRepository.class);
    MigrationReportDocument report =
        MigrationReportDocument.processing(MIGRATION_ID, Instant.parse("2026-10-11T10:00:00Z"));
    report.recordStarted(
        Instant.parse("2026-10-11T10:00:00Z"),
        "alice",
        new MigrationSource("MANIFEST", "root", "manifest.csv"));
    when(reports.findById(MIGRATION_ID.toString())).thenReturn(Optional.of(report));

    AuditEventDocument later =
        event(
            EventType.FILE_TRANSFER_COMPLETED,
            "file-2",
            "2026-10-11T10:02:00Z",
            new Document("status", "STORED"));
    AuditEventDocument tiedLaterByType =
        event(
            EventType.INTEGRITY_VALIDATION_COMPLETED,
            "file-1",
            "2026-10-11T10:01:00Z",
            new Document("status", "PASSED"));
    AuditEventDocument tiedEarlierByType =
        event(
            EventType.FILE_DISCOVERY_COMPLETED,
            "file-1",
            "2026-10-11T10:01:00Z",
            new Document("status", "ACCEPTED"));
    when(events.findByMigrationIdOrderByOccurredAtAsc(eq(MIGRATION_ID.toString()), any()))
        .thenAnswer(
            invocation ->
                new PageImpl<>(
                    List.of(later, tiedLaterByType, tiedEarlierByType),
                    invocation.getArgument(1),
                    3));

    List<UserTimelineResponse> timeline =
        new ReportQueryService(reports, events)
            .ownedTimeline("alice", MIGRATION_ID, PageRequest.of(0, 20))
            .getContent();

    assertEquals(
        List.of("DISCOVERY", "INTEGRITY", "TRANSFER"),
        timeline.stream().map(UserTimelineResponse::stage).toList());
  }

  private AuditEventDocument event(
      EventType type, String fileId, String occurredAt, Document data) {
    AuditEventDocument event = mock(AuditEventDocument.class);
    when(event.getEventType()).thenReturn(type);
    when(event.getFileId()).thenReturn(fileId);
    when(event.getOccurredAt()).thenReturn(Instant.parse(occurredAt));
    when(event.getData()).thenReturn(data);
    return event;
  }
}

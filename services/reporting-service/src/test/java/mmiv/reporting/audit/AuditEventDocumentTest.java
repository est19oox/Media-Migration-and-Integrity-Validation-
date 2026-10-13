package mmiv.reporting.audit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import mmiv.reporting.contract.EventContractValidator;
import mmiv.reporting.contract.EventType;
import mmiv.reporting.contract.SourceService;
import mmiv.reporting.contract.ValidatedEvent;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AuditEventDocumentTest {

  private static final UUID EVENT_ID = UUID.fromString("5bd98576-0707-4b83-850d-945dfe33e606");

  private static final UUID MIGRATION_ID = UUID.fromString("c49f2f8f-ad97-45a8-8867-f3ca8a971d80");

  private static final Instant OCCURRED_AT = Instant.parse("2026-10-11T10:00:00Z");

  private static final Instant RECEIVED_AT = Instant.parse("2026-10-11T10:00:01Z");

  private static final Instant APPLIED_AT = Instant.parse("2026-10-11T10:00:02Z");

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  private final EventContractValidator validator = new EventContractValidator(objectMapper);

  @Test
  void createsReceivedAuditDocumentFromValidatedEvent() {
    ValidatedEvent event = validatedMigrationStartedEvent();

    AuditEventDocument audit = AuditEventDocument.received(event, RECEIVED_AT);

    assertAll(
        () -> assertEquals(EVENT_ID.toString(), audit.getId()),
        () -> assertEquals(EventType.MIGRATION_STARTED, audit.getEventType()),
        () -> assertEquals(2, audit.getSchemaVersion()),
        () -> assertEquals(SourceService.DISCOVERY_SERVICE, audit.getSourceService()),
        () -> assertEquals(MIGRATION_ID.toString(), audit.getMigrationId()),
        () -> assertNull(audit.getFileId()),
        () -> assertEquals(OCCURRED_AT, audit.getOccurredAt()),
        () -> assertEquals(RECEIVED_AT, audit.getReceivedAt()),
        () -> assertEquals(AuditProcessingStatus.RECEIVED, audit.getProcessingStatus()),
        () -> assertNull(audit.getAppliedAt()),
        () -> assertEquals(event.payloadSha256(), audit.getPayloadSha256()),
        () -> assertArrayEquals(event.originalBody(), audit.getOriginalBody()));
  }

  @Test
  void protectsStoredEventDataFromExternalMutation() {
    ValidatedEvent event = validatedMigrationStartedEvent();

    AuditEventDocument audit = AuditEventDocument.received(event, RECEIVED_AT);

    Document returnedData = audit.getData();
    returnedData.put("initiatedBy", "changed-user");

    Document returnedSource = returnedData.get("source", Document.class);
    returnedSource.put("rootId", "changed-root");

    assertEquals("user-123", audit.getData().getString("initiatedBy"));

    assertEquals("legacy-media", audit.getData().get("source", Document.class).getString("rootId"));
  }

  @Test
  void protectsOriginalBodyFromExternalMutation() {
    ValidatedEvent event = validatedMigrationStartedEvent();

    AuditEventDocument audit = AuditEventDocument.received(event, RECEIVED_AT);

    byte[] returnedBody = audit.getOriginalBody();
    returnedBody[0] = 'X';

    assertEquals((byte) '{', audit.getOriginalBody()[0]);
  }

  @Test
  void markAppliedOnlyChangesProcessingMetadata() {
    ValidatedEvent event = validatedMigrationStartedEvent();

    AuditEventDocument audit = AuditEventDocument.received(event, RECEIVED_AT);

    String originalHash = audit.getPayloadSha256();
    Document originalData = audit.getData();

    audit.markApplied(APPLIED_AT);

    assertAll(
        () -> assertEquals(AuditProcessingStatus.APPLIED, audit.getProcessingStatus()),
        () -> assertEquals(APPLIED_AT, audit.getAppliedAt()),
        () -> assertEquals(RECEIVED_AT, audit.getReceivedAt()),
        () -> assertEquals(originalHash, audit.getPayloadSha256()),
        () -> assertEquals(originalData, audit.getData()));
  }

  private ValidatedEvent validatedMigrationStartedEvent() {
    String json =
        """
                {
                  "eventId": "5bd98576-0707-4b83-850d-945dfe33e606",
                  "eventType": "MIGRATION_STARTED",
                  "schemaVersion": 2,
                  "sourceService": "discovery-service",
                  "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
                  "occurredAt": "2026-10-11T10:00:00Z",
                  "data": {
                    "initiatedBy": "user-123",
                    "source": {
                      "type": "MANIFEST",
                      "rootId": "legacy-media",
                      "manifestName": "semester-1-media.csv"
                    }
                  }
                }
                """;

    return validator.validate(json.getBytes(StandardCharsets.UTF_8));
  }
}

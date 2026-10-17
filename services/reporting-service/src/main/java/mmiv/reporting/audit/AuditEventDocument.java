package mmiv.reporting.audit;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import mmiv.reporting.contract.EventEnvelope;
import mmiv.reporting.contract.EventType;
import mmiv.reporting.contract.SourceService;
import mmiv.reporting.contract.ValidatedEvent;
import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

@org.springframework.data.mongodb.core.mapping.Document(collection = "audit_events")
@CompoundIndexes({
  @CompoundIndex(name = "migration_occurred_idx", def = "{'migrationId': 1, 'occurredAt': 1}"),
  @CompoundIndex(name = "migration_file_idx", def = "{'migrationId': 1, 'fileId': 1}")
})
public class AuditEventDocument {

  @Id private final String id;

  private final EventType eventType;

  private final int schemaVersion;

  private final SourceService sourceService;

  private final String migrationId;

  private final String fileId;

  private final Instant occurredAt;

  private final Document data;

  private final byte[] originalBody;

  private final String payloadSha256;

  private AuditProcessingStatus processingStatus;

  private final Instant receivedAt;

  private Instant appliedAt;

  private AuditEventDocument(
      String id,
      EventType eventType,
      int schemaVersion,
      SourceService sourceService,
      String migrationId,
      String fileId,
      Instant occurredAt,
      Document data,
      byte[] originalBody,
      String payloadSha256,
      AuditProcessingStatus processingStatus,
      Instant receivedAt,
      Instant appliedAt) {
    this.id = Objects.requireNonNull(id);
    this.eventType = Objects.requireNonNull(eventType);
    this.schemaVersion = schemaVersion;
    this.sourceService = Objects.requireNonNull(sourceService);
    this.migrationId = Objects.requireNonNull(migrationId);
    this.fileId = fileId;
    this.occurredAt = Objects.requireNonNull(occurredAt);
    this.data = copyDocument(Objects.requireNonNull(data));
    this.originalBody = Arrays.copyOf(Objects.requireNonNull(originalBody), originalBody.length);
    this.payloadSha256 = Objects.requireNonNull(payloadSha256);
    this.processingStatus = Objects.requireNonNull(processingStatus);
    this.receivedAt = Objects.requireNonNull(receivedAt);
    this.appliedAt = appliedAt;
  }

  public static AuditEventDocument received(ValidatedEvent event, Instant receivedAt) {
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(receivedAt, "receivedAt must not be null");

    EventEnvelope envelope = event.envelope();

    String fileId = envelope.fileId() == null ? null : envelope.fileId().toString();

    return new AuditEventDocument(
        envelope.eventId().toString(),
        envelope.eventType(),
        envelope.schemaVersion(),
        envelope.sourceService(),
        envelope.migrationId().toString(),
        fileId,
        envelope.occurredAt(),
        Document.parse(envelope.data().toString()),
        event.originalBody(),
        event.payloadSha256(),
        AuditProcessingStatus.RECEIVED,
        receivedAt,
        null);
  }

  public boolean hasSamePayload(String checksum) {
    return payloadSha256.equals(checksum);
  }

  public boolean isApplied() {
    return processingStatus == AuditProcessingStatus.APPLIED;
  }

  public void markApplied(Instant appliedAt) {
    this.processingStatus = AuditProcessingStatus.APPLIED;

    this.appliedAt = Objects.requireNonNull(appliedAt, "appliedAt must not be null");
  }

  public String getId() {
    return id;
  }

  public EventType getEventType() {
    return eventType;
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public SourceService getSourceService() {
    return sourceService;
  }

  public String getMigrationId() {
    return migrationId;
  }

  public String getFileId() {
    return fileId;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public Document getData() {
    return copyDocument(data);
  }

  public byte[] getOriginalBody() {
    return Arrays.copyOf(originalBody, originalBody.length);
  }

  public String getPayloadSha256() {
    return payloadSha256;
  }

  public AuditProcessingStatus getProcessingStatus() {
    return processingStatus;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public Instant getAppliedAt() {
    return appliedAt;
  }

  private static Document copyDocument(Document source) {
    return Document.parse(source.toJson());
  }
}

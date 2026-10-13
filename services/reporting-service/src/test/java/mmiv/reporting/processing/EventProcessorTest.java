package mmiv.reporting.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import mmiv.reporting.audit.AuditEventDocument;
import mmiv.reporting.audit.AuditEventRepository;
import mmiv.reporting.audit.AuditProcessingStatus;
import mmiv.reporting.contract.EventContractValidator;
import mmiv.reporting.contract.ValidatedEvent;
import mmiv.reporting.report.ReportProjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;

class EventProcessorTest {

  private static final Instant NOW = Instant.parse("2026-10-11T11:00:00Z");

  private final EventContractValidator validator =
      new EventContractValidator(JsonMapper.builder().build());

  private final AtomicReference<AuditEventDocument> storedAudit = new AtomicReference<>();

  private AuditEventRepository auditRepository;

  private ReportProjector projector;

  private EventProcessor processor;
  private mmiv.reporting.report.VerificationCleanupGuard cleanup;

  @BeforeEach
  void setUp() {
    storedAudit.set(null);
    auditRepository = mock(AuditEventRepository.class);
    projector = mock(ReportProjector.class);

    when(auditRepository.findById(anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(storedAudit.get()));

    when(auditRepository.insert(any(AuditEventDocument.class)))
        .thenAnswer(
            invocation -> {
              AuditEventDocument audit = invocation.getArgument(0);
              storedAudit.set(audit);
              return audit;
            });

    when(auditRepository.save(any(AuditEventDocument.class)))
        .thenAnswer(
            invocation -> {
              AuditEventDocument audit = invocation.getArgument(0);
              storedAudit.set(audit);
              return audit;
            });

    cleanup = mock(mmiv.reporting.report.VerificationCleanupGuard.class);
    processor =
        new EventProcessor(
            validator, auditRepository, projector, Clock.fixed(NOW, ZoneOffset.UTC), cleanup);
  }

  @Test
  void cleanedMigrationEventsAreAcknowledgedWithoutRecreatingAuditData() {
    when(cleanup.isDeleted(any(java.util.UUID.class))).thenReturn(true);
    assertEquals(EventProcessingResult.IGNORED_CLEANED, processor.process(eventBody()));
    org.mockito.Mockito.verifyNoInteractions(auditRepository, projector);
  }

  @Test
  void newEventIsAuditedProjectedThenMarkedApplied() {
    byte[] body = eventBody();

    assertEquals(EventProcessingResult.APPLIED, processor.process(body));
    assertEquals(AuditProcessingStatus.APPLIED, storedAudit.get().getProcessingStatus());
    assertEquals(NOW, storedAudit.get().getAppliedAt());

    InOrder order = inOrder(auditRepository, projector);
    order.verify(auditRepository).findById(anyString());
    order.verify(auditRepository).insert(any(AuditEventDocument.class));
    order.verify(projector).apply(any(ValidatedEvent.class));
    order.verify(auditRepository).findById(anyString());
    order.verify(auditRepository).save(any(AuditEventDocument.class));
  }

  @Test
  void identicalAppliedEventIsSuccessfulDuplicate() {
    AuditEventDocument audit = receivedAudit(eventBody());
    audit.markApplied(NOW.minusSeconds(1));
    storedAudit.set(audit);

    assertEquals(EventProcessingResult.DUPLICATE_APPLIED, processor.process(eventBody()));

    verify(projector, never()).apply(any(ValidatedEvent.class));
    verify(auditRepository, never()).insert(any(AuditEventDocument.class));
    verify(auditRepository, never()).save(any(AuditEventDocument.class));
  }

  @Test
  void identicalReceivedEventResumesProjection() {
    storedAudit.set(receivedAudit(eventBody()));

    assertEquals(EventProcessingResult.APPLIED, processor.process(eventBody()));

    verify(auditRepository, never()).insert(any(AuditEventDocument.class));
    verify(projector).apply(any(ValidatedEvent.class));
    assertEquals(AuditProcessingStatus.APPLIED, storedAudit.get().getProcessingStatus());
  }

  @Test
  void reusedEventIdWithDifferentPayloadIsRejected() {
    storedAudit.set(receivedAudit(eventBody()));

    byte[] conflictingBody =
        new String(eventBody(), StandardCharsets.UTF_8)
            .replace("user-123", "user-456")
            .getBytes(StandardCharsets.UTF_8);

    assertThrows(EventIntegrityConflictException.class, () -> processor.process(conflictingBody));

    verify(projector, never()).apply(any(ValidatedEvent.class));
  }

  @Test
  void projectionFailureLeavesAuditReceived() {
    org.mockito.Mockito.doThrow(new IllegalStateException("projection failed"))
        .when(projector)
        .apply(any(ValidatedEvent.class));

    assertThrows(IllegalStateException.class, () -> processor.process(eventBody()));

    assertEquals(AuditProcessingStatus.RECEIVED, storedAudit.get().getProcessingStatus());
    verify(auditRepository, never()).save(any(AuditEventDocument.class));
  }

  @Test
  void replayAfterMarkAppliedFailureProjectsSafelyAgain() {
    AuditEventDocument firstPersisted = receivedAudit(eventBody());
    AuditEventDocument persistedReceived = receivedAudit(eventBody());

    when(auditRepository.findById(anyString()))
        .thenReturn(
            Optional.empty(),
            Optional.of(firstPersisted),
            Optional.of(persistedReceived),
            Optional.of(persistedReceived));
    when(auditRepository.insert(any(AuditEventDocument.class))).thenReturn(firstPersisted);
    when(auditRepository.save(any(AuditEventDocument.class)))
        .thenThrow(new IllegalStateException("save failed"))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertThrows(IllegalStateException.class, () -> processor.process(eventBody()));

    assertEquals(EventProcessingResult.APPLIED, processor.process(eventBody()));
    verify(projector, times(2)).apply(any(ValidatedEvent.class));
  }

  @Test
  void duplicateKeyRaceReloadsTheWinningAudit() {
    AuditEventDocument winner = receivedAudit(eventBody());
    winner.markApplied(NOW.minusSeconds(1));

    when(auditRepository.findById(anyString())).thenReturn(Optional.empty(), Optional.of(winner));
    when(auditRepository.insert(any(AuditEventDocument.class)))
        .thenThrow(new DuplicateKeyException("concurrent insert"));

    assertEquals(EventProcessingResult.DUPLICATE_APPLIED, processor.process(eventBody()));
    verify(projector, never()).apply(any(ValidatedEvent.class));
  }

  private AuditEventDocument receivedAudit(byte[] body) {
    return AuditEventDocument.received(validator.validate(body), NOW.minusSeconds(10));
  }

  private byte[] eventBody() {
    return """
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
                """
        .getBytes(StandardCharsets.UTF_8);
  }
}

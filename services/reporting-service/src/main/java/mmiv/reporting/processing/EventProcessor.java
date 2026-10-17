package mmiv.reporting.processing;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import mmiv.reporting.audit.AuditEventDocument;
import mmiv.reporting.audit.AuditEventRepository;
import mmiv.reporting.contract.EventContractValidator;
import mmiv.reporting.contract.ValidatedEvent;
import mmiv.reporting.report.ReportProjector;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class EventProcessor {

  private final EventContractValidator validator;

  private final AuditEventRepository auditRepository;

  private final ReportProjector projector;

  private final Clock clock;

  private final mmiv.reporting.report.VerificationCleanupGuard cleanup;

  public EventProcessor(
      EventContractValidator validator,
      AuditEventRepository auditRepository,
      ReportProjector projector,
      Clock clock,
      mmiv.reporting.report.VerificationCleanupGuard cleanup) {
    this.cleanup = Objects.requireNonNull(cleanup);
    this.validator = Objects.requireNonNull(validator, "validator must not be null");
    this.auditRepository =
        Objects.requireNonNull(auditRepository, "auditRepository must not be null");
    this.projector = Objects.requireNonNull(projector, "projector must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public EventProcessingResult process(byte[] body) {
    ValidatedEvent event = validator.validate(body);
    if (cleanup.isDeleted(event.envelope().migrationId()))
      return EventProcessingResult.IGNORED_CLEANED;
    String eventId = event.envelope().eventId().toString();

    Optional<AuditEventDocument> existing = auditRepository.findById(eventId);

    if (existing.isPresent()) {
      EventProcessingResult duplicateResult = inspectExisting(existing.get(), event);
      if (duplicateResult != null) {
        return duplicateResult;
      }
    } else {
      EventProcessingResult raceResult = insertReceived(event);
      if (raceResult != null) {
        return raceResult;
      }
    }

    projector.apply(event);
    if (cleanup.isDeleted(event.envelope().migrationId())) {
      cleanup.clean(event.envelope().migrationId());
      return EventProcessingResult.IGNORED_CLEANED;
    }

    AuditEventDocument stored =
        auditRepository
            .findById(eventId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Audit event disappeared before mark-applied: " + eventId));

    assertSamePayload(stored, event);
    stored.markApplied(clock.instant());
    auditRepository.save(stored);
    if (cleanup.isDeleted(event.envelope().migrationId())) {
      cleanup.clean(event.envelope().migrationId());
      return EventProcessingResult.IGNORED_CLEANED;
    }

    return EventProcessingResult.APPLIED;
  }

  private EventProcessingResult insertReceived(ValidatedEvent event) {
    try {
      auditRepository.insert(AuditEventDocument.received(event, clock.instant()));
      return null;
    } catch (DuplicateKeyException exception) {
      String eventId = event.envelope().eventId().toString();

      AuditEventDocument winner = auditRepository.findById(eventId).orElseThrow(() -> exception);

      return inspectExisting(winner, event);
    }
  }

  private EventProcessingResult inspectExisting(AuditEventDocument existing, ValidatedEvent event) {
    assertSamePayload(existing, event);

    if (existing.isApplied()) {
      return EventProcessingResult.DUPLICATE_APPLIED;
    }

    return null;
  }

  private void assertSamePayload(AuditEventDocument existing, ValidatedEvent event) {
    if (!existing.hasSamePayload(event.payloadSha256())) {
      throw new EventIntegrityConflictException(
          "eventId was reused with different content: " + existing.getId());
    }
  }
}

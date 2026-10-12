package mmiv.reporting.processing;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import mmiv.reporting.audit.AuditEventDocument;
import mmiv.reporting.audit.AuditEventRepository;
import mmiv.reporting.audit.AuditProcessingStatus;
import mmiv.reporting.contract.EventContractValidator;
import mmiv.reporting.contract.ValidatedEvent;
import mmiv.reporting.report.ReportProjector;

@Service
public class EventProcessor {

    private final EventContractValidator validator;

    private final AuditEventRepository auditRepository;

    private final ReportProjector projector;

    private final Clock clock;

    public EventProcessor(
            EventContractValidator validator,
            AuditEventRepository auditRepository,
            ReportProjector projector,
            Clock clock
    ) {
        this.validator = Objects.requireNonNull(
                validator,
                "validator must not be null"
        );
        this.auditRepository = Objects.requireNonNull(
                auditRepository,
                "auditRepository must not be null"
        );
        this.projector = Objects.requireNonNull(
                projector,
                "projector must not be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock must not be null"
        );
    }

    public EventProcessingResult process(byte[] body) {
        ValidatedEvent event = validator.validate(body);
        String eventId =
                event.envelope().eventId().toString();

        Optional<AuditEventDocument> existing =
                auditRepository.findById(eventId);

        if (existing.isPresent()) {
            EventProcessingResult duplicateResult =
                    inspectExisting(existing.get(), event);
            if (duplicateResult != null) {
                return duplicateResult;
            }
        } else {
            EventProcessingResult raceResult =
                    insertReceived(event);
            if (raceResult != null) {
                return raceResult;
            }
        }

        projector.apply(event);

        AuditEventDocument stored =
                auditRepository.findById(eventId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Audit event disappeared before mark-applied: "
                                                + eventId
                                )
                        );

        assertSamePayload(stored, event);
        stored.markApplied(clock.instant());
        auditRepository.save(stored);

        return EventProcessingResult.APPLIED;
    }

    private EventProcessingResult insertReceived(
            ValidatedEvent event
    ) {
        try {
            auditRepository.insert(
                    AuditEventDocument.received(
                            event,
                            clock.instant()
                    )
            );
            return null;
        } catch (DuplicateKeyException exception) {
            String eventId =
                    event.envelope().eventId().toString();

            AuditEventDocument winner =
                    auditRepository.findById(eventId)
                            .orElseThrow(() -> exception);

            return inspectExisting(winner, event);
        }
    }

    private EventProcessingResult inspectExisting(
            AuditEventDocument existing,
            ValidatedEvent event
    ) {
        assertSamePayload(existing, event);

        if (existing.getProcessingStatus()
                == AuditProcessingStatus.APPLIED) {
            return EventProcessingResult.DUPLICATE_APPLIED;
        }

        return null;
    }

    private void assertSamePayload(
            AuditEventDocument existing,
            ValidatedEvent event
    ) {
        if (!existing.getPayloadSha256()
                .equals(event.payloadSha256())) {
            throw new EventIntegrityConflictException(
                    "eventId was reused with different content: "
                            + existing.getId()
            );
        }
    }
}

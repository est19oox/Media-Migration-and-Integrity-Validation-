package mmiv.reporting.contract;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record EventEnvelope(
    UUID eventId,
    EventType eventType,
    int schemaVersion,
    SourceService sourceService,
    UUID migrationId,
    UUID fileId,
    Instant occurredAt,
    JsonNode data) {}

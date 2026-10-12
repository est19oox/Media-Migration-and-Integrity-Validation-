package mmiv.reporting.contract;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.params.provider.CsvSource;

class EventContractValidatorTest {

    private final ObjectMapper objectMapper =
            JsonMapper.builder().build();

    private final EventContractValidator validator =
            new EventContractValidator(objectMapper);

    @Test
    void parsesValidMigrationStartedEvent() {
        String json = """
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

        ValidatedEvent event = validator.validate(
                json.getBytes(StandardCharsets.UTF_8)
        );

        EventEnvelope envelope = event.envelope();

        assertAll(
                () -> assertEquals(
                        UUID.fromString("5bd98576-0707-4b83-850d-945dfe33e606"),
                        envelope.eventId()
                ),
                () -> assertEquals(
                        EventType.MIGRATION_STARTED,
                        envelope.eventType()
                ),
                () -> assertEquals(2, envelope.schemaVersion()),
                () -> assertEquals(
                        SourceService.DISCOVERY_SERVICE,
                        envelope.sourceService()
                ),
                () -> assertEquals(
                        UUID.fromString("c49f2f8f-ad97-45a8-8867-f3ca8a971d80"),
                        envelope.migrationId()
                ),
                () -> assertNull(envelope.fileId()),
                () -> assertEquals(
                        Instant.parse("2026-10-11T10:00:00Z"),
                        envelope.occurredAt()
                ),
                () -> assertEquals(
                        "user-123",
                        envelope.data().path("initiatedBy").stringValue()
                ),
                () -> assertEquals(
                        "legacy-media",
                        envelope.data().path("source").path("rootId").stringValue()
                )
        );
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        String json = """
                {
                  "eventId": "5bd98576-0707-4b83-850d-945dfe33e606",
                  "eventType": "MIGRATION_STARTED",
                  "schemaVersion": 1,
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

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        json.getBytes(StandardCharsets.UTF_8)
                )
        );

        assertTrue(
                exception.getMessage().contains("schemaVersion"),
                "Validation error must identify schemaVersion"
        );
    }

    @Test
    void requiresFileIdForFileLevelEvent() {
        String json = """
                {
                  "eventId": "4f2daf80-2da3-4e40-a151-c23cd591e678",
                  "eventType": "FILE_TRANSFER_COMPLETED",
                  "schemaVersion": 2,
                  "sourceService": "transfer-service",
                  "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
                  "occurredAt": "2026-10-11T10:20:00Z",
                  "data": {
                    "transferId": "344cd216-dac8-4bd2-a350-1dc891c9f4cb",
                    "status": "STORED",
                    "durationMs": 2300
                  }
                }
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        json.getBytes(StandardCharsets.UTF_8)
                )
        );

        assertTrue(
                exception.getMessage().contains("fileId"),
                "Validation error must identify fileId"
        );
    }

    @Test
    void rejectsEventPublishedByWrongService() {
        String json = """
                {
                  "eventId": "e33e8af7-56f1-45ec-94fd-76da981dfe29",
                  "eventType": "INTEGRITY_VALIDATION_COMPLETED",
                  "schemaVersion": 2,
                  "sourceService": "transfer-service",
                  "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
                  "fileId": "9eceb6d4-8a39-48da-8abe-1238ba8c7a67",
                  "occurredAt": "2026-10-11T10:30:00Z",
                  "data": {
                    "transferId": "344cd216-dac8-4bd2-a350-1dc891c9f4cb",
                    "status": "PASSED",
                    "checksumAlgorithm": "SHA-256",
                    "durationMs": 3200
                  }
                }
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        json.getBytes(StandardCharsets.UTF_8)
                )
        );

        assertTrue(
                exception.getMessage().contains("sourceService"),
                "Validation error must identify sourceService"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/etc/passwd",
            "C:/media/video.mp4",
            "team/../secret.mp4",
            "team\\video.mp4",
            "team//video.mp4"
    })
    void rejectsUnsafeRelativePath(String relativePath) throws Exception {
        String json = """
                {
                  "eventId": "d8a74831-65e7-41d0-9072-efac30acf67d",
                  "eventType": "FILE_DISCOVERY_COMPLETED",
                  "schemaVersion": 2,
                  "sourceService": "discovery-service",
                  "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
                  "fileId": "9eceb6d4-8a39-48da-8abe-1238ba8c7a67",
                  "occurredAt": "2026-10-11T10:01:00Z",
                  "data": {
                    "status": "ACCEPTED",
                    "manifestMediaId": "MEDIA-001",
                    "fileName": "video.mp4",
                    "mediaType": "VIDEO",
                    "format": "MP4",
                    "sizeBytes": 204800
                  }
                }
                """;

        ObjectNode event = (ObjectNode) objectMapper.readTree(json);
        ObjectNode data = (ObjectNode) event.path("data");
        data.put("relativePath", relativePath);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        objectMapper.writeValueAsBytes(event)
                )
        );

        assertTrue(
                exception.getMessage().contains("relativePath"),
                "Validation error must identify relativePath"
        );
    }

    @Test
    void rejectsUnknownTopLevelField() throws Exception {
        String json = """
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

        ObjectNode event = (ObjectNode) objectMapper.readTree(json);
        event.put("extra", true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        objectMapper.writeValueAsBytes(event)
                )
        );

        assertTrue(
                exception.getMessage().contains("extra"),
                "Validation error must identify the unknown field"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sourceChecksum",
            "destinationChecksum",
            "absolutePath",
            "sourcePath"
    })
    void rejectsForbiddenDataFieldAtAnyDepth(
            String forbiddenField
    ) throws Exception {
        String json = """
                {
                  "eventId": "e33e8af7-56f1-45ec-94fd-76da981dfe29",
                  "eventType": "INTEGRITY_VALIDATION_COMPLETED",
                  "schemaVersion": 2,
                  "sourceService": "integrity-service",
                  "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
                  "fileId": "9eceb6d4-8a39-48da-8abe-1238ba8c7a67",
                  "occurredAt": "2026-10-11T10:30:00Z",
                  "data": {
                    "transferId": "344cd216-dac8-4bd2-a350-1dc891c9f4cb",
                    "status": "PASSED",
                    "checksumAlgorithm": "SHA-256",
                    "durationMs": 3200
                  }
                }
                """;

        ObjectNode event = (ObjectNode) objectMapper.readTree(json);
        ObjectNode data = (ObjectNode) event.path("data");
        ObjectNode nestedDebugData =
                objectMapper.createObjectNode();

        nestedDebugData.put(forbiddenField, "sensitive-value");
        data.set("debug", nestedDebugData);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        objectMapper.writeValueAsBytes(event)
                )
        );

        assertTrue(
                exception.getMessage().contains(forbiddenField),
                "Validation error must identify the forbidden field"
        );
    }

    @Test
    void preservesOriginalBodyAndCalculatesPayloadHash() {
        String json = """
                {"eventId":"5bd98576-0707-4b83-850d-945dfe33e606","eventType":"MIGRATION_STARTED","schemaVersion":2,"sourceService":"discovery-service","migrationId":"c49f2f8f-ad97-45a8-8867-f3ca8a971d80","occurredAt":"2026-10-11T10:00:00Z","data":{"initiatedBy":"user-123","source":{"type":"MANIFEST","rootId":"legacy-media","manifestName":"semester-1-media.csv"}}}
                """.strip();

        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ValidatedEvent event = validator.validate(body);

        assertArrayEquals(body, event.originalBody());
        assertEquals(
                "a93347e136e752275b334587cde1c79eb1e0f555f4116ac74975406484906694",
                event.payloadSha256()
        );

        body[0] = 'X';
        assertEquals(
                (byte) '{',
                event.originalBody()[0],
                "ValidatedEvent must copy the input body"
        );

        byte[] returnedBody = event.originalBody();
        returnedBody[0] = 'X';

        assertEquals(
                (byte) '{',
                event.originalBody()[0],
                "ValidatedEvent must not expose its internal byte array"
        );
    }

    @ParameterizedTest(name = "rejects missing required field: {0}")
    @ValueSource(strings = {
            "eventId",
            "eventType",
            "schemaVersion",
            "sourceService",
            "migrationId",
            "occurredAt",
            "data"
    })
    void rejectsMissingRequiredTopLevelField(
            String fieldName
    ) throws Exception {
        String json = """
            {
              "eventId": "5bd98576-0707-4b83-850d-945dfe33e606",
              "eventType": "MIGRATION_STARTED",
              "schemaVersion": 2,
              "sourceService": "discovery-service",
              "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
              "occurredAt": "2026-10-11T10:00:00Z",
              "data": {}
            }
            """;

        ObjectNode event =
                (ObjectNode) objectMapper.readTree(json);

        event.remove(fieldName);

        EventContractException exception = assertThrows(
                EventContractException.class,
                () -> validator.validate(
                        objectMapper.writeValueAsBytes(event)
                )
        );

        assertTrue(
                exception.getMessage().contains(fieldName),
                "Validation error must identify missing field: "
                        + fieldName
        );
    }

    @Test
    void rejectsFileIdForMigrationLevelEvent() {
        String json = """
            {
              "eventId": "5bd98576-0707-4b83-850d-945dfe33e606",
              "eventType": "MIGRATION_STARTED",
              "schemaVersion": 2,
              "sourceService": "discovery-service",
              "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
              "fileId": "9eceb6d4-8a39-48da-8abe-1238ba8c7a67",
              "occurredAt": "2026-10-11T10:00:00Z",
              "data": {}
            }
            """;

        EventContractException exception = assertThrows(
                EventContractException.class,
                () -> validator.validate(
                        json.getBytes(StandardCharsets.UTF_8)
                )
        );

        assertTrue(
                exception.getMessage().contains("fileId"),
                "Migration-level events must reject fileId"
        );
    }

    @ParameterizedTest(name = "rejects non-object data: {0}")
    @ValueSource(strings = {
            "\"text\"",
            "[]",
            "123",
            "true"
    })
    void rejectsNonObjectData(String invalidData) {
        String json = """
            {
              "eventId": "5bd98576-0707-4b83-850d-945dfe33e606",
              "eventType": "MIGRATION_STARTED",
              "schemaVersion": 2,
              "sourceService": "discovery-service",
              "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
              "occurredAt": "2026-10-11T10:00:00Z",
              "data": %s
            }
            """.formatted(invalidData);

        EventContractException exception = assertThrows(
                EventContractException.class,
                () -> validator.validate(
                        json.getBytes(StandardCharsets.UTF_8)
                )
        );

        assertTrue(
                exception.getMessage().contains("data"),
                "Validation error must identify data"
        );
    }

    @ParameterizedTest(
            name = "{0} rejects unsupported status"
    )
    @CsvSource({
            "FILE_DISCOVERY_COMPLETED,discovery-service,true",
            "MIGRATION_DISCOVERY_COMPLETED,discovery-service,false",
            "FILE_TRANSFER_COMPLETED,transfer-service,true",
            "INTEGRITY_VALIDATION_COMPLETED,integrity-service,true"
    })
    void rejectsUnsupportedEventStatus(
            String eventType,
            String sourceService,
            boolean fileLevel
    ) {
        String fileIdField = fileLevel
                ? """
              "fileId": "9eceb6d4-8a39-48da-8abe-1238ba8c7a67",
              """
                : "";

        String json = """
            {
              "eventId": "5bd98576-0707-4b83-850d-945dfe33e606",
              "eventType": "%s",
              "schemaVersion": 2,
              "sourceService": "%s",
              "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
              %s
              "occurredAt": "2026-10-11T10:00:00Z",
              "data": {
                "status": "UNKNOWN"
              }
            }
            """.formatted(
                eventType,
                sourceService,
                fileIdField
        );

        EventContractException exception = assertThrows(
                EventContractException.class,
                () -> validator.validate(
                        json.getBytes(StandardCharsets.UTF_8)
                )
        );

        assertTrue(
                exception.getMessage().contains("status"),
                "Validation error must identify status"
        );
    }

    @ParameterizedTest(
            name = "{0} status {2} requires failure"
    )
    @CsvSource({
            "FILE_DISCOVERY_COMPLETED,discovery-service,REJECTED,true",
            "FILE_DISCOVERY_COMPLETED,discovery-service,ERROR,true",
            "MIGRATION_DISCOVERY_COMPLETED,discovery-service,FAILED,false",
            "FILE_TRANSFER_COMPLETED,transfer-service,FAILED,true",
            "INTEGRITY_VALIDATION_COMPLETED,integrity-service,FAILED,true",
            "INTEGRITY_VALIDATION_COMPLETED,integrity-service,ERROR,true"
    })
    void requiresFailureObjectForUnsuccessfulStatus(
            String eventType,
            String sourceService,
            String status,
            boolean fileLevel
    ) {
        String fileIdField = fileLevel
                ? """
              "fileId": "9eceb6d4-8a39-48da-8abe-1238ba8c7a67",
              """
                : "";

        String json = """
            {
              "eventId": "5bd98576-0707-4b83-850d-945dfe33e606",
              "eventType": "%s",
              "schemaVersion": 2,
              "sourceService": "%s",
              "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
              %s
              "occurredAt": "2026-10-11T10:00:00Z",
              "data": {
                "status": "%s"
              }
            }
            """.formatted(
                eventType,
                sourceService,
                fileIdField,
                status
        );

        EventContractException exception = assertThrows(
                EventContractException.class,
                () -> validator.validate(
                        json.getBytes(StandardCharsets.UTF_8)
                )
        );

        assertTrue(
                exception.getMessage().contains("failure"),
                "Validation error must identify failure"
        );
    }

    @ParameterizedTest(
            name = "failure requires field: {0}"
    )
    @ValueSource(strings = {
            "code",
            "message",
            "retryable"
    })
    void requiresAllFailureFields(
            String fieldName
    ) throws Exception {
        String json = """
            {
              "eventId": "4f2daf80-2da3-4e40-a151-c23cd591e678",
              "eventType": "FILE_TRANSFER_COMPLETED",
              "schemaVersion": 2,
              "sourceService": "transfer-service",
              "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
              "fileId": "9eceb6d4-8a39-48da-8abe-1238ba8c7a67",
              "occurredAt": "2026-10-11T10:20:00Z",
              "data": {
                "status": "FAILED",
                "failure": {
                  "code": "S3_UPLOAD_FAILED",
                  "message": "Destination upload failed",
                  "retryable": true
                }
              }
            }
            """;

        ObjectNode event =
                (ObjectNode) objectMapper.readTree(json);

        ObjectNode failure = (ObjectNode) event
                .path("data")
                .path("failure");

        failure.remove(fieldName);

        EventContractException exception = assertThrows(
                EventContractException.class,
                () -> validator.validate(
                        objectMapper.writeValueAsBytes(event)
                )
        );

        assertTrue(
                exception.getMessage().contains(fieldName),
                "Validation error must identify missing failure field: "
                        + fieldName
        );
    }

    @ParameterizedTest(
            name = "rejects invalid failure field type: {0}"
    )
    @ValueSource(strings = {
            "code",
            "message",
            "retryable"
    })
    void rejectsInvalidFailureFieldType(
            String fieldName
    ) throws Exception {
        String json = """
            {
              "eventId": "4f2daf80-2da3-4e40-a151-c23cd591e678",
              "eventType": "FILE_TRANSFER_COMPLETED",
              "schemaVersion": 2,
              "sourceService": "transfer-service",
              "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
              "fileId": "9eceb6d4-8a39-48da-8abe-1238ba8c7a67",
              "occurredAt": "2026-10-11T10:20:00Z",
              "data": {
                "status": "FAILED",
                "failure": {
                  "code": "S3_UPLOAD_FAILED",
                  "message": "Destination upload failed",
                  "retryable": true
                }
              }
            }
            """;

        ObjectNode event =
                (ObjectNode) objectMapper.readTree(json);

        ObjectNode failure = (ObjectNode) event
                .path("data")
                .path("failure");

        String invalidJsonValue = switch (fieldName) {
            case "code" -> "123";
            case "message" -> "true";
            case "retryable" -> "\"yes\"";
            default -> throw new IllegalArgumentException(
                    "Unexpected field: " + fieldName
            );
        };

        failure.set(
                fieldName,
                objectMapper.readTree(invalidJsonValue)
        );

        EventContractException exception = assertThrows(
                EventContractException.class,
                () -> validator.validate(
                        objectMapper.writeValueAsBytes(event)
                )
        );

        assertTrue(
                exception.getMessage().contains(fieldName),
                "Validation error must identify invalid field: "
                        + fieldName
        );
    }

    @ParameterizedTest(
            name = "completed discovery requires count: {0}"
    )
    @ValueSource(strings = {
            "totalFiles",
            "acceptedFiles",
            "rejectedFiles"
    })
    void completedDiscoveryRequiresAllCounts(
            String fieldName
    ) throws Exception {
        String json = """
            {
              "eventId": "1da1aef1-0385-4a26-8f0a-dcf98a5233c1",
              "eventType": "MIGRATION_DISCOVERY_COMPLETED",
              "schemaVersion": 2,
              "sourceService": "discovery-service",
              "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
              "occurredAt": "2026-10-11T10:02:00Z",
              "data": {
                "status": "COMPLETED",
                "totalFiles": 100,
                "acceptedFiles": 95,
                "rejectedFiles": 5
              }
            }
            """;

        ObjectNode event =
                (ObjectNode) objectMapper.readTree(json);

        ObjectNode data =
                (ObjectNode) event.path("data");

        data.remove(fieldName);

        EventContractException exception = assertThrows(
                EventContractException.class,
                () -> validator.validate(
                        objectMapper.writeValueAsBytes(event)
                )
        );

        assertTrue(
                exception.getMessage().contains(fieldName),
                "Validation error must identify missing count: "
                        + fieldName
        );
    }

    @ParameterizedTest(name = "rejects negative discovery count: {0}")
    @ValueSource(strings = {
            "totalFiles",
            "acceptedFiles",
            "rejectedFiles"
    })
    void rejectsNegativeDiscoveryCount(String fieldName)
            throws Exception {
        ObjectNode event = completedDiscoveryEvent();
        ((ObjectNode) event.path("data")).put(fieldName, -1);

        assertInvalid(event, fieldName);
    }

    @ParameterizedTest(name = "rejects non-integer discovery count: {0}")
    @ValueSource(strings = {
            "totalFiles",
            "acceptedFiles",
            "rejectedFiles"
    })
    void rejectsNonIntegerDiscoveryCount(String fieldName)
            throws Exception {
        ObjectNode event = completedDiscoveryEvent();
        ((ObjectNode) event.path("data")).put(fieldName, 1.5);

        assertInvalid(event, fieldName);
    }

    @Test
    void rejectsDiscoveryCountsThatDoNotAddUp() throws Exception {
        ObjectNode event = completedDiscoveryEvent();
        ObjectNode data = (ObjectNode) event.path("data");
        data.put("acceptedFiles", 94);

        assertInvalid(event, "totalFiles");
    }

    @ParameterizedTest(name = "{0} requires data field {1}")
    @CsvSource({
            "MIGRATION_STARTED,initiatedBy",
            "MIGRATION_STARTED,source",
            "FILE_DISCOVERY_COMPLETED,fileName",
            "FILE_DISCOVERY_COMPLETED,relativePath",
            "FILE_DISCOVERY_COMPLETED,mediaType",
            "FILE_DISCOVERY_COMPLETED,format",
            "FILE_DISCOVERY_COMPLETED,sizeBytes",
            "FILE_TRANSFER_COMPLETED,transferId",
            "FILE_TRANSFER_COMPLETED,durationMs",
            "INTEGRITY_VALIDATION_COMPLETED,transferId",
            "INTEGRITY_VALIDATION_COMPLETED,checksumAlgorithm",
            "INTEGRITY_VALIDATION_COMPLETED,durationMs"
    })
    void requiresEventSpecificDataField(
            String eventType,
            String fieldName
    ) throws Exception {
        ObjectNode event = validEvent(eventType);
        ((ObjectNode) event.path("data")).remove(fieldName);

        assertInvalid(event, fieldName);
    }

    @ParameterizedTest(name = "MIGRATION_STARTED source requires {0}")
    @ValueSource(strings = {"type", "rootId", "manifestName"})
    void requiresMigrationSourceField(String fieldName)
            throws Exception {
        ObjectNode event = validEvent("MIGRATION_STARTED");
        ObjectNode source = (ObjectNode) event
                .path("data")
                .path("source");
        source.remove(fieldName);

        assertInvalid(event, fieldName);
    }

    @ParameterizedTest(name = "rejects negative numeric field {1} for {0}")
    @CsvSource({
            "FILE_DISCOVERY_COMPLETED,sizeBytes",
            "FILE_TRANSFER_COMPLETED,durationMs",
            "INTEGRITY_VALIDATION_COMPLETED,durationMs"
    })
    void rejectsNegativeEventNumericField(
            String eventType,
            String fieldName
    ) throws Exception {
        ObjectNode event = validEvent(eventType);
        ((ObjectNode) event.path("data")).put(fieldName, -1);

        assertInvalid(event, fieldName);
    }

    @ParameterizedTest(name = "rejects failure on successful {0}")
    @ValueSource(strings = {
            "FILE_DISCOVERY_COMPLETED",
            "MIGRATION_DISCOVERY_COMPLETED",
            "FILE_TRANSFER_COMPLETED",
            "INTEGRITY_VALIDATION_COMPLETED"
    })
    void rejectsFailureOnSuccessfulStatus(String eventType)
            throws Exception {
        ObjectNode event = validEvent(eventType);
        ObjectNode failure = objectMapper.createObjectNode();
        failure.put("code", "UNEXPECTED");
        failure.put("message", "Should not be present");
        failure.put("retryable", false);
        ((ObjectNode) event.path("data")).set("failure", failure);

        assertInvalid(event, "failure");
    }

    @ParameterizedTest(name = "rejects blank failure field {0}")
    @ValueSource(strings = {"code", "message"})
    void rejectsBlankFailureText(String fieldName)
            throws Exception {
        ObjectNode event = failedTransferEvent();
        ObjectNode failure = (ObjectNode) event
                .path("data")
                .path("failure");
        failure.put(fieldName, "   ");

        assertInvalid(event, fieldName);
    }

    @Test
    void rejectsFailureCodeOwnedByAnotherService() throws Exception {
        ObjectNode event = failedTransferEvent();
        ObjectNode failure = (ObjectNode) event
                .path("data")
                .path("failure");
        failure.put("code", "CHECKSUM_MISMATCH");

        assertInvalid(event, "code");
    }

    @ParameterizedTest(name = "rejects unknown data field for {0}")
    @ValueSource(strings = {
            "MIGRATION_STARTED",
            "FILE_DISCOVERY_COMPLETED",
            "MIGRATION_DISCOVERY_COMPLETED",
            "FILE_TRANSFER_COMPLETED",
            "INTEGRITY_VALIDATION_COMPLETED"
    })
    void rejectsUnknownEventDataField(String eventType)
            throws Exception {
        ObjectNode event = validEvent(eventType);
        ((ObjectNode) event.path("data")).put("unexpected", true);

        assertInvalid(event, "unexpected");
    }

    @Test
    void acceptsDirectUploadMigrationSource() throws Exception {
        ObjectNode event = validEvent("MIGRATION_STARTED");
        ObjectNode source = (ObjectNode) event
                .path("data")
                .path("source");
        source.put("type", "DIRECT_UPLOAD");
        source.remove("manifestName");

        ValidatedEvent validated = validator.validate(
                objectMapper.writeValueAsBytes(event)
        );

        assertEquals(
                EventType.MIGRATION_STARTED,
                validated.envelope().eventType()
        );
    }

    private void assertInvalid(
            ObjectNode event,
            String expectedField
    ) throws Exception {
        EventContractException exception = assertThrows(
                EventContractException.class,
                () -> validator.validate(
                        objectMapper.writeValueAsBytes(event)
                )
        );

        assertTrue(
                exception.getMessage().contains(expectedField),
                "Validation error must identify: " + expectedField
        );
    }

    private ObjectNode completedDiscoveryEvent()
            throws Exception {
        return validEvent("MIGRATION_DISCOVERY_COMPLETED");
    }

    private ObjectNode failedTransferEvent() throws Exception {
        ObjectNode event = validEvent("FILE_TRANSFER_COMPLETED");
        ObjectNode data = (ObjectNode) event.path("data");
        data.put("status", "FAILED");

        ObjectNode failure = objectMapper.createObjectNode();
        failure.put("code", "S3_UPLOAD_FAILED");
        failure.put("message", "Destination upload failed");
        failure.put("retryable", true);
        data.set("failure", failure);

        return event;
    }

    private ObjectNode validEvent(String eventType)
            throws Exception {
        String json = switch (eventType) {
            case "MIGRATION_STARTED" -> """
                    {
                      "eventId":"5bd98576-0707-4b83-850d-945dfe33e606",
                      "eventType":"MIGRATION_STARTED",
                      "schemaVersion":2,
                      "sourceService":"discovery-service",
                      "migrationId":"c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
                      "occurredAt":"2026-10-11T10:00:00Z",
                      "data":{"initiatedBy":"user-123","source":{"type":"MANIFEST","rootId":"legacy-media","manifestName":"semester-1-media.csv"}}
                    }
                    """;
            case "FILE_DISCOVERY_COMPLETED" -> """
                    {
                      "eventId":"d8a74831-65e7-41d0-9072-efac30acf67d",
                      "eventType":"FILE_DISCOVERY_COMPLETED",
                      "schemaVersion":2,
                      "sourceService":"discovery-service",
                      "migrationId":"c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
                      "fileId":"9eceb6d4-8a39-48da-8abe-1238ba8c7a67",
                      "occurredAt":"2026-10-11T10:01:00Z",
                      "data":{"status":"ACCEPTED","manifestMediaId":"MEDIA-001","fileName":"report.pdf","relativePath":"department-a/report.pdf","mediaType":"DOCUMENT","format":"PDF","sizeBytes":204800}
                    }
                    """;
            case "MIGRATION_DISCOVERY_COMPLETED" -> """
                    {
                      "eventId":"1da1aef1-0385-4a26-8f0a-dcf98a5233c1",
                      "eventType":"MIGRATION_DISCOVERY_COMPLETED",
                      "schemaVersion":2,
                      "sourceService":"discovery-service",
                      "migrationId":"c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
                      "occurredAt":"2026-10-11T10:02:00Z",
                      "data":{"status":"COMPLETED","totalFiles":100,"acceptedFiles":95,"rejectedFiles":5}
                    }
                    """;
            case "FILE_TRANSFER_COMPLETED" -> """
                    {
                      "eventId":"4f2daf80-2da3-4e40-a151-c23cd591e678",
                      "eventType":"FILE_TRANSFER_COMPLETED",
                      "schemaVersion":2,
                      "sourceService":"transfer-service",
                      "migrationId":"c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
                      "fileId":"9eceb6d4-8a39-48da-8abe-1238ba8c7a67",
                      "occurredAt":"2026-10-11T10:20:00Z",
                      "data":{"transferId":"344cd216-dac8-4bd2-a350-1dc891c9f4cb","status":"STORED","durationMs":2300}
                    }
                    """;
            case "INTEGRITY_VALIDATION_COMPLETED" -> """
                    {
                      "eventId":"e33e8af7-56f1-45ec-94fd-76da981dfe29",
                      "eventType":"INTEGRITY_VALIDATION_COMPLETED",
                      "schemaVersion":2,
                      "sourceService":"integrity-service",
                      "migrationId":"c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
                      "fileId":"9eceb6d4-8a39-48da-8abe-1238ba8c7a67",
                      "occurredAt":"2026-10-11T10:30:00Z",
                      "data":{"transferId":"344cd216-dac8-4bd2-a350-1dc891c9f4cb","status":"PASSED","checksumAlgorithm":"SHA-256","durationMs":3200}
                    }
                    """;
            default -> throw new IllegalArgumentException(
                    "Unsupported test event: " + eventType
            );
        };

        return (ObjectNode) objectMapper.readTree(json);
    }

}

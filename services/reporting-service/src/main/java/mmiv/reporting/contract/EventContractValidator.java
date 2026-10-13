package mmiv.reporting.contract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class EventContractValidator {

  private static final int SUPPORTED_SCHEMA_VERSION = 2;

  private static final Set<String> ALLOWED_TOP_LEVEL_FIELDS =
      Set.of(
          "eventId",
          "eventType",
          "schemaVersion",
          "sourceService",
          "migrationId",
          "fileId",
          "occurredAt",
          "data");

  private static final Set<String> REQUIRED_TOP_LEVEL_FIELDS =
      Set.of(
          "eventId",
          "eventType",
          "schemaVersion",
          "sourceService",
          "migrationId",
          "occurredAt",
          "data");

  private static final Set<String> FORBIDDEN_DATA_FIELDS =
      Set.of("sourceChecksum", "destinationChecksum", "absolutePath", "sourcePath");

  private final ObjectMapper objectMapper;

  public EventContractValidator(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  public ValidatedEvent validate(byte[] body) {
    Objects.requireNonNull(body, "body must not be null");

    try {
      JsonNode root = objectMapper.readTree(body);

      validateTopLevelFields(root);

      int schemaVersion = root.path("schemaVersion").intValue();
      if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
        throw new EventContractException("schemaVersion must equal " + SUPPORTED_SCHEMA_VERSION);
      }

      EventType eventType = EventType.valueOf(root.path("eventType").stringValue());

      SourceService sourceService =
          SourceService.fromWireValue(root.path("sourceService").stringValue());

      if (eventType.publisher() != sourceService) {
        throw new EventContractException("sourceService does not match eventType publisher");
      }

      UUID fileId = root.has("fileId") ? UUID.fromString(root.path("fileId").stringValue()) : null;

      if (eventType.fileLevel() && fileId == null) {
        throw new EventContractException("fileId is required for file-level events");
      }

      if (!eventType.fileLevel() && fileId != null) {
        throw new EventContractException("fileId must be omitted for migration-level events");
      }

      JsonNode data = root.path("data");

      if (!data.isObject()) {
        throw new EventContractException("data must be a JSON object");
      }

      validateForbiddenDataFields(data);
      validateEventData(eventType, data);

      EventEnvelope envelope =
          new EventEnvelope(
              UUID.fromString(root.path("eventId").stringValue()),
              eventType,
              schemaVersion,
              sourceService,
              UUID.fromString(root.path("migrationId").stringValue()),
              fileId,
              Instant.parse(root.path("occurredAt").stringValue()),
              data);

      return new ValidatedEvent(envelope, body, sha256(body));
    } catch (EventContractException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new EventContractException("Invalid MMIV event", exception);
    }
  }

  private static void validateEventData(EventType eventType, JsonNode data) {
    validateAllowedDataFields(eventType, data);

    if (eventType == EventType.MIGRATION_STARTED) {
      validateMigrationStarted(data);
      return;
    }

    String status = requireText(data, "status");

    Set<String> allowedStatuses =
        switch (eventType) {
          case FILE_DISCOVERY_COMPLETED -> Set.of("ACCEPTED", "REJECTED", "ERROR");

          case MIGRATION_DISCOVERY_COMPLETED -> Set.of("COMPLETED", "FAILED");

          case FILE_TRANSFER_COMPLETED -> Set.of("STORED", "FAILED");

          case INTEGRITY_VALIDATION_COMPLETED -> Set.of("PASSED", "FAILED", "ERROR");

          case MIGRATION_STARTED ->
              throw new IllegalStateException("MIGRATION_STARTED does not have a status");
        };

    if (!allowedStatuses.contains(status)) {
      throw new EventContractException("Unsupported status for " + eventType + ": " + status);
    }

    Set<String> successfulStatuses = Set.of("ACCEPTED", "COMPLETED", "STORED", "PASSED");

    if (successfulStatuses.contains(status)) {
      if (data.has("failure")) {
        throw new EventContractException(
            "failure must be omitted for successful status: " + status);
      }
    } else {
      JsonNode failure = data.path("failure");

      if (!failure.isObject()) {
        throw new EventContractException("failure object is required for status: " + status);
      }

      validateFailureObject(eventType, failure);
    }

    switch (eventType) {
      case FILE_DISCOVERY_COMPLETED -> validateFileDiscovery(data, status);
      case MIGRATION_DISCOVERY_COMPLETED -> validateMigrationDiscovery(data, status);
      case FILE_TRANSFER_COMPLETED -> validateTransfer(data);
      case INTEGRITY_VALIDATION_COMPLETED -> validateIntegrity(data);
      case MIGRATION_STARTED ->
          throw new IllegalStateException("MIGRATION_STARTED was already validated");
    }
  }

  private static void validateMigrationStarted(JsonNode data) {
    requireText(data, "initiatedBy");

    JsonNode source = data.path("source");
    if (!source.isObject()) {
      throw new EventContractException("source must be a JSON object");
    }

    validateAllowedFields(source, Set.of("type", "rootId", "manifestName"), "source");

    String sourceType = requireText(source, "type");
    requireText(source, "rootId");

    if ("MANIFEST".equals(sourceType)) {
      requireText(source, "manifestName");
      return;
    }

    if ("DIRECT_UPLOAD".equals(sourceType)) {
      if (source.has("manifestName")) {
        throw new EventContractException("manifestName must be omitted for DIRECT_UPLOAD");
      }
      return;
    }

    throw new EventContractException("Unsupported source type: " + sourceType);
  }

  private static void validateFileDiscovery(JsonNode data, String status) {
    requireText(data, "fileName");

    if (data.has("manifestMediaId")) {
      requireText(data, "manifestMediaId");
    }

    if ("ACCEPTED".equals(status)) {
      String relativePath = requireText(data, "relativePath");
      requireText(data, "mediaType");
      requireText(data, "format");
      requireNonNegativeInteger(data, "sizeBytes");

      if (!isValidRelativePath(relativePath)) {
        throw new EventContractException("relativePath must be a normalized relative path");
      }
      return;
    }

    if (data.has("relativePath")) {
      String relativePath = requireText(data, "relativePath");
      if (!isValidRelativePath(relativePath)) {
        throw new EventContractException("relativePath must be a normalized relative path");
      }
    }
  }

  private static void validateMigrationDiscovery(JsonNode data, String status) {
    if ("COMPLETED".equals(status)) {
      validateDiscoveryCounts(data);
      return;
    }

    if (data.has("totalFiles") || data.has("acceptedFiles") || data.has("rejectedFiles")) {
      validateDiscoveryCounts(data);
    }
  }

  private static void validateTransfer(JsonNode data) {
    requireUuid(data, "transferId");
    requireNonNegativeInteger(data, "durationMs");
  }

  private static void validateIntegrity(JsonNode data) {
    requireUuid(data, "transferId");
    requireText(data, "checksumAlgorithm");
    requireNonNegativeInteger(data, "durationMs");
  }

  private static void validateAllowedDataFields(EventType eventType, JsonNode data) {
    Set<String> allowedFields =
        switch (eventType) {
          case MIGRATION_STARTED -> Set.of("initiatedBy", "source");
          case FILE_DISCOVERY_COMPLETED ->
              Set.of(
                  "status",
                  "manifestMediaId",
                  "fileName",
                  "relativePath",
                  "mediaType",
                  "format",
                  "sizeBytes",
                  "failure");
          case MIGRATION_DISCOVERY_COMPLETED ->
              Set.of("status", "totalFiles", "acceptedFiles", "rejectedFiles", "failure");
          case FILE_TRANSFER_COMPLETED -> Set.of("transferId", "status", "durationMs", "failure");
          case INTEGRITY_VALIDATION_COMPLETED ->
              Set.of("transferId", "status", "checksumAlgorithm", "durationMs", "failure");
        };

    validateAllowedFields(data, allowedFields, "data");
  }

  private static void validateAllowedFields(
      JsonNode object, Set<String> allowedFields, String context) {
    for (String fieldName : object.propertyNames()) {
      if (!allowedFields.contains(fieldName)) {
        throw new EventContractException("Unknown " + context + " field: " + fieldName);
      }
    }
  }

  private static String requireText(JsonNode object, String fieldName) {
    JsonNode value = object.path(fieldName);

    if (!value.isString() || value.stringValue().isBlank()) {
      throw new EventContractException(fieldName + " must be a non-blank string");
    }

    return value.stringValue();
  }

  private static void requireUuid(JsonNode object, String fieldName) {
    String value = requireText(object, fieldName);

    try {
      UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new EventContractException(fieldName + " must be a UUID", exception);
    }
  }

  private static long requireNonNegativeInteger(JsonNode object, String fieldName) {
    JsonNode value = object.path(fieldName);

    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
      throw new EventContractException(fieldName + " must be a non-negative integer");
    }

    return value.longValue();
  }

  private static boolean isValidRelativePath(String value) {
    if (value == null
        || value.isBlank()
        || value.contains("\\")
        || value.startsWith("/")
        || value.matches("^[A-Za-z]:.*")) {
      return false;
    }

    return Arrays.stream(value.split("/", -1))
        .noneMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."));
  }

  private static void validateTopLevelFields(JsonNode root) {
    for (String fieldName : REQUIRED_TOP_LEVEL_FIELDS) {
      if (!root.has(fieldName) || root.path(fieldName).isNull()) {
        throw new EventContractException("Missing required field: " + fieldName);
      }
    }

    for (String fieldName : root.propertyNames()) {
      if (!ALLOWED_TOP_LEVEL_FIELDS.contains(fieldName)) {
        throw new EventContractException("Unknown top-level field: " + fieldName);
      }
    }
  }

  private static void validateForbiddenDataFields(JsonNode node) {
    if (node.isObject()) {
      for (var property : node.properties()) {
        String fieldName = property.getKey();

        if (FORBIDDEN_DATA_FIELDS.contains(fieldName)) {
          throw new EventContractException("Forbidden data field: " + fieldName);
        }

        validateForbiddenDataFields(property.getValue());
      }

      return;
    }

    if (node.isArray()) {
      node.forEach(EventContractValidator::validateForbiddenDataFields);
    }
  }

  private static String sha256(byte[] body) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");

      return HexFormat.of().formatHex(digest.digest(body));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static void validateFailureObject(EventType eventType, JsonNode failure) {
    Set<String> requiredFields = Set.of("code", "message", "retryable");

    for (String fieldName : requiredFields) {
      if (!failure.has(fieldName) || failure.path(fieldName).isNull()) {
        throw new EventContractException("Missing required failure field: " + fieldName);
      }
    }

    validateAllowedFields(failure, requiredFields, "failure");

    if (!failure.path("code").isString()) {
      throw new EventContractException("failure code must be a string");
    }

    if (!failure.path("message").isString()) {
      throw new EventContractException("failure message must be a string");
    }

    if (!failure.path("retryable").isBoolean()) {
      throw new EventContractException("failure retryable must be a boolean");
    }

    String code = failure.path("code").stringValue();
    String message = failure.path("message").stringValue();

    if (code.isBlank()) {
      throw new EventContractException("failure code must not be blank");
    }

    if (message.isBlank()) {
      throw new EventContractException("failure message must not be blank");
    }

    Set<String> allowedCodes =
        switch (eventType) {
          case FILE_DISCOVERY_COMPLETED ->
              Set.of(
                  "FILE_NOT_FOUND",
                  "AMBIGUOUS_FILE_NAME",
                  "UNSUPPORTED_FILE_TYPE",
                  "FILE_SIZE_MISMATCH",
                  "FILE_TYPE_MISMATCH",
                  "INACCESSIBLE_FILE",
                  "INVALID_FILE_METADATA",
                  "DISCOVERY_ERROR");
          case MIGRATION_DISCOVERY_COMPLETED ->
              Set.of(
                  "INVALID_MANIFEST",
                  "SOURCE_DIRECTORY_NOT_FOUND",
                  "SOURCE_DIRECTORY_INACCESSIBLE",
                  "DISCOVERY_JOB_ERROR");
          case FILE_TRANSFER_COMPLETED ->
              Set.of(
                  "SOURCE_NOT_FOUND",
                  "SOURCE_NOT_READABLE",
                  "INVALID_DESTINATION",
                  "S3_UPLOAD_FAILED",
                  "TRANSFER_TIMEOUT",
                  "TRANSFER_ERROR");
          case INTEGRITY_VALIDATION_COMPLETED ->
              Set.of(
                  "CHECKSUM_MISMATCH",
                  "DESTINATION_OBJECT_NOT_FOUND",
                  "DESTINATION_READ_FAILED",
                  "INVALID_SOURCE_CHECKSUM",
                  "VALIDATION_TIMEOUT",
                  "VALIDATION_ERROR");
          case MIGRATION_STARTED -> Set.of();
        };

    if (!allowedCodes.contains(code)) {
      throw new EventContractException("failure code is not valid for " + eventType + ": " + code);
    }
  }

  private static void validateDiscoveryCounts(JsonNode data) {
    Set<String> requiredCountFields = Set.of("totalFiles", "acceptedFiles", "rejectedFiles");

    for (String fieldName : requiredCountFields) {
      if (!data.has(fieldName) || data.path(fieldName).isNull()) {
        throw new EventContractException("Missing required discovery count: " + fieldName);
      }
    }

    long totalFiles = requireNonNegativeInteger(data, "totalFiles");
    long acceptedFiles = requireNonNegativeInteger(data, "acceptedFiles");
    long rejectedFiles = requireNonNegativeInteger(data, "rejectedFiles");

    if (acceptedFiles > totalFiles
        || rejectedFiles > totalFiles
        || acceptedFiles != totalFiles - rejectedFiles) {
      throw new EventContractException("acceptedFiles + rejectedFiles must equal totalFiles");
    }
  }
}

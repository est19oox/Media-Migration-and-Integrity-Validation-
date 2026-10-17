package mmiv.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "INTEGRITY_VALIDATION_LOG", schema = "INTEGRITY_SERVICE")
public class IntegrityValidationLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "VALIDATION_ID")
  private Long validationId;

  @Column(name = "BUCKET_NAME", nullable = false, length = 255)
  private String bucketName;

  @Column(name = "OBJECT_KEY", nullable = false, length = 1024)
  private String objectKey;

  @Column(name = "SOURCE_CHECKSUM", nullable = false, length = 64)
  private String sourceChecksum;

  @Column(name = "DESTINATION_CHECKSUM", length = 64)
  private String destinationChecksum;

  @Column(name = "ALGORITHM", nullable = false, length = 20)
  private String algorithm;

  @Column(name = "VALIDATION_STATUS", nullable = false, length = 20)
  private String validationStatus;

  @Column(name = "ERROR_MESSAGE", length = 2000)
  private String errorMessage;

  @Column(name = "VALIDATED_AT", nullable = false)
  private LocalDateTime validatedAt;

  /** Start a validation aggregate before performing external checksum I/O. */
  public static IntegrityValidationLog start(String bucket, String key, String checksum) {
    var validation = new IntegrityValidationLog();
    validation.bucketName = bucket;
    validation.objectKey = key;
    validation.sourceChecksum = checksum;
    validation.algorithm = "SHA-256";
    return validation;
  }

  public void compareWith(String destination, LocalDateTime now) {
    requirePending();
    destinationChecksum = destination;
    validationStatus = sourceChecksum.equalsIgnoreCase(destination) ? "PASSED" : "FAILED";
    validatedAt = java.util.Objects.requireNonNull(now);
  }

  public void fail(String message, LocalDateTime now) {
    requirePending();
    validationStatus = "ERROR";
    errorMessage = message;
    validatedAt = java.util.Objects.requireNonNull(now);
  }

  public boolean passed() {
    return "PASSED".equals(validationStatus);
  }

  private void requirePending() {
    if (validationStatus != null) throw new IllegalStateException("Validation is already finished");
  }

  public Long getValidationId() {
    return validationId;
  }

  public String getBucketName() {
    return bucketName;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public String getSourceChecksum() {
    return sourceChecksum;
  }

  public String getDestinationChecksum() {
    return destinationChecksum;
  }

  public String getAlgorithm() {
    return algorithm;
  }

  public String getValidationStatus() {
    return validationStatus;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public LocalDateTime getValidatedAt() {
    return validatedAt;
  }
}

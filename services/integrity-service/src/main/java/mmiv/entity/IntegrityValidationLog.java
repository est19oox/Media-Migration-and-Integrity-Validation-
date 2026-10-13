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

  public Long getValidationId() {
    return validationId;
  }

  public void setValidationId(Long validationId) {
    this.validationId = validationId;
  }

  public String getBucketName() {
    return bucketName;
  }

  public void setBucketName(String bucketName) {
    this.bucketName = bucketName;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public void setObjectKey(String objectKey) {
    this.objectKey = objectKey;
  }

  public String getSourceChecksum() {
    return sourceChecksum;
  }

  public void setSourceChecksum(String sourceChecksum) {
    this.sourceChecksum = sourceChecksum;
  }

  public String getDestinationChecksum() {
    return destinationChecksum;
  }

  public void setDestinationChecksum(String destinationChecksum) {
    this.destinationChecksum = destinationChecksum;
  }

  public String getAlgorithm() {
    return algorithm;
  }

  public void setAlgorithm(String algorithm) {
    this.algorithm = algorithm;
  }

  public String getValidationStatus() {
    return validationStatus;
  }

  public void setValidationStatus(String validationStatus) {
    this.validationStatus = validationStatus;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public LocalDateTime getValidatedAt() {
    return validatedAt;
  }

  public void setValidatedAt(LocalDateTime validatedAt) {
    this.validatedAt = validatedAt;
  }
}

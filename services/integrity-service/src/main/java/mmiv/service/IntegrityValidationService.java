package mmiv.service;

import java.io.IOException;
import java.time.LocalDateTime;
import mmiv.dto.ValidationResponse;
import mmiv.entity.IntegrityValidationLog;
import mmiv.repository.IntegrityValidationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IntegrityValidationService {

  private static final Logger logger = LoggerFactory.getLogger(IntegrityValidationService.class);

  private final S3ChecksumService s3ChecksumService;
  private final IntegrityValidationLogRepository validationLogRepository;
  private final io.micrometer.core.instrument.MeterRegistry metrics;

  public IntegrityValidationService(
      S3ChecksumService s3ChecksumService,
      IntegrityValidationLogRepository validationLogRepository,
      io.micrometer.core.instrument.MeterRegistry metrics) {

    this.s3ChecksumService = s3ChecksumService;
    this.validationLogRepository = validationLogRepository;
    this.metrics = metrics;
  }

  public ValidationResponse validate(String bucket, String objectKey, String sourceChecksum)
      throws IOException {

    long started = System.nanoTime();
    String outcome = "ERROR";
    logger.info("Starting integrity validation: bucket={}, objectKey={}", bucket, objectKey);

    var auditLog = IntegrityValidationLog.start(bucket, objectKey, sourceChecksum);
    try {

      String destinationChecksum = s3ChecksumService.calculateSHA256(bucket, objectKey);

      auditLog.compareWith(destinationChecksum, LocalDateTime.now());
      boolean valid = auditLog.passed();
      String status = auditLog.getValidationStatus();

      logger.info(
          "SHA-256 validation result: objectKey={}, sourceChecksum={}, destinationChecksum={},"
              + " status={}",
          objectKey,
          sourceChecksum,
          destinationChecksum,
          status);

      validationLogRepository.save(auditLog);

      logger.info(
          "Integrity validation audit record saved: objectKey={}, status={}", objectKey, status);

      outcome = status;
      return new ValidationResponse(
          bucket, objectKey, "SHA-256", sourceChecksum, destinationChecksum, valid);

    } catch (IOException e) {

      auditLog.fail(e.getMessage(), LocalDateTime.now());

      validationLogRepository.save(auditLog);

      logger.error("Integrity validation error: objectKey={}", objectKey, e);

      throw e;
    } finally {
      io.micrometer.core.instrument.Timer.builder("mmiv.integrity.validation")
          .description("End-to-end checksum validation duration")
          .tag("outcome", outcome)
          .publishPercentileHistogram()
          .register(metrics)
          .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
  }
}

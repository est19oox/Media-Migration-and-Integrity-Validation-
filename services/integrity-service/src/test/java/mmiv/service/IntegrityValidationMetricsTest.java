package mmiv.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import mmiv.repository.IntegrityValidationLogRepository;
import org.junit.jupiter.api.Test;

class IntegrityValidationMetricsTest {
  @Test
  void recordsDurationSeparatelyForSuccessfulAndFailedChecks() throws Exception {
    var checksum = mock(S3ChecksumService.class);
    var repository = mock(IntegrityValidationLogRepository.class);
    var metrics = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    try {
      var service = new IntegrityValidationService(checksum, repository, metrics);
      when(checksum.calculateSHA256("bucket", "one")).thenReturn("same");
      when(checksum.calculateSHA256("bucket", "two")).thenThrow(new IOException("unavailable"));

      assertTrue(service.validate("bucket", "one", "same").valid());
      assertThrows(IOException.class, () -> service.validate("bucket", "two", "same"));

      var passed = metrics.get("mmiv.integrity.validation").tag("outcome", "PASSED").timer();
      var error = metrics.get("mmiv.integrity.validation").tag("outcome", "ERROR").timer();
      assertEquals(1, passed.count());
      assertEquals(1, error.count());
      assertTrue(passed.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) > 0);
      assertTrue(error.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) > 0);
      assertTrue(metrics.scrape().contains("mmiv_integrity_validation_seconds_bucket"));
    } finally {
      metrics.close();
    }
  }
}

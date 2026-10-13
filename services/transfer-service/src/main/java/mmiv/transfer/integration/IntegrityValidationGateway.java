package mmiv.transfer.integration;

import feign.FeignException;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class IntegrityValidationGateway {
  private final IntegrityClient client;
  private final int maxAttempts;
  private final long retryDelayMs;

  public IntegrityValidationGateway(
      IntegrityClient client,
      @org.springframework.beans.factory.annotation.Value("${mmiv.integrity.max-attempts:3}")
          int maxAttempts,
      @org.springframework.beans.factory.annotation.Value("${mmiv.integrity.retry-delay-ms:1000}")
          long retryDelayMs) {
    if (maxAttempts < 1 || maxAttempts > 10 || retryDelayMs < 0 || retryDelayMs > 5000) {
      throw new IllegalArgumentException("Integrity attempts must be 1–10 and delay 0–5000 ms");
    }
    this.client = client;
    this.maxAttempts = maxAttempts;
    this.retryDelayMs = retryDelayMs;
  }

  public record Outcome(String validationResult, boolean success, String message) {}

  public Outcome validate(String transferId, String bucket, String key, String checksum) {
    var request = new IntegrityValidationRequest(transferId, bucket, key, checksum);
    Outcome last = unavailable();
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        last = validateOnce(request);
      } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException open) {
        return unavailable();
      }
      if (last.success() || attempt == maxAttempts) return last;
      try {
        Thread.sleep(retryDelayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return unavailable();
      }
    }
    return last;
  }

  private Outcome validateOnce(IntegrityValidationRequest request) {
    String transferId = request.transferId();
    String bucket = request.bucket();
    String key = request.objectKey();
    String checksum = request.sourceChecksum();
    try {
      var response =
          mmiv.resilience.DependencyCircuits.breaker("integrity", "eureka:integrity-service")
              .executeSupplier(
                  () -> {
                    try {
                      return client.validate(request);
                    } catch (FeignException error) {
                      if (error.status() >= 400
                          && error.status() < 500
                          && error.status() != 408
                          && error.status() != 429)
                        throw new mmiv.resilience.DependencyCircuits.NonAvailabilityFailure(error);
                      throw error;
                    }
                  });
      if (response == null
          || !Objects.equals(transferId, response.transferId())
          || !Objects.equals(bucket, response.bucket())
          || !Objects.equals(key, response.objectKey())
          || !"SHA-256".equals(response.algorithm())
          || checksum == null
          || !checksum.equalsIgnoreCase(response.sourceChecksum())
          || response.destinationChecksum() == null
          || !response.destinationChecksum().matches("(?i)[0-9a-f]{64}")
          || response.valid() == null) {
        return unavailable();
      }
      boolean matches = checksum.equalsIgnoreCase(response.destinationChecksum());
      if (matches != response.valid()
          || !(matches ? "PASSED" : "FAILED").equals(response.validationResult())) {
        return unavailable();
      }
      return matches
          ? new Outcome("PASSED", true, "Transfer completed and integrity verified")
          : new Outcome("FAILED", false, "File uploaded, but integrity validation failed");
    } catch (FeignException | mmiv.resilience.DependencyCircuits.NonAvailabilityFailure e) {
      // Timeouts, discovery failures, HTTP errors and decoding errors never imply success.
      return unavailable();
    }
  }

  private static Outcome unavailable() {
    return new Outcome(
        "UNAVAILABLE", false, "Integrity could not be confirmed after validation attempts");
  }
}

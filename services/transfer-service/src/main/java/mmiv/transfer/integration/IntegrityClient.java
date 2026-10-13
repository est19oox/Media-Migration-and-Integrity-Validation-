package mmiv.transfer.integration;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Eureka resolves integrity-service; no fixed host or gateway URL. */
@FeignClient(name = "integrity-service")
public interface IntegrityClient {
  @PostMapping(value = "/api/integrity/validate", consumes = "application/json")
  IntegrityValidationResponse validate(@RequestBody IntegrityValidationRequest request);
}

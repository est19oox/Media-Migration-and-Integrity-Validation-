package mmiv.integration;

import java.util.*;
import mmiv.model.MetadataPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VerificationCleanupService {
  private final MetadataPlanRepository repository;
  private final PermissionClient permissions;
  private final RestClient transfer;
  private final RestClient reporting;

  public VerificationCleanupService(
      MetadataPlanRepository repository,
      PermissionClient permissions,
      @Value("${mmiv.transfer.url:http://transfer-service}") String transferUrl,
      @Value("${mmiv.reporting.url:http://reporting-service}") String reportingUrl,
      mmiv.resilience.EurekaHttpClients.EurekaRestClientFactory clients) {
    this.repository = repository;
    this.permissions = permissions;
    transfer = clients.create("transfer", transferUrl, java.time.Duration.ofMinutes(5));
    reporting = clients.create("reporting", reportingUrl, java.time.Duration.ofMinutes(5));
  }

  public boolean closed(Jwt jwt, UUID run) {
    permissions.requireActive(jwt.getTokenValue());
    return repository.isClosed(jwt.getSubject(), run);
  }

  public List<MetadataPlan> plans(Jwt jwt, UUID run) {
    permissions.requireActive(jwt.getTokenValue());
    var plans = repository.verificationPlans(jwt.getSubject(), run);
    for (var plan : plans)
      permissions.require(jwt.getTokenValue(), jwt.getSubject(), plan.destinationId(), "WRITE");
    return plans;
  }

  public Object clean(Jwt jwt, UUID run) {
    plans(jwt, run); // Authorize before closing or deleting anything.
    repository.closeRun(jwt.getSubject(), run);
    try {
      transfer
          .post()
          .uri("/api/v1/verification/{run}/cleanup", run)
          .header("Authorization", "Bearer " + jwt.getTokenValue())
          .retrieve()
          .toBodilessEntity();
      reporting
          .post()
          .uri("/api/verification/{run}/cleanup", run)
          .header("Authorization", "Bearer " + jwt.getTokenValue())
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException error) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Verification cleanup incomplete; retry with the same run ID");
    }
    int deleted = repository.deleteVerificationPlans(jwt.getSubject(), run);
    try {
      transfer
          .post()
          .uri("/api/v1/verification/{run}/finish", run)
          .header("Authorization", "Bearer " + jwt.getTokenValue())
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Verification folder cleanup incomplete; retry with the same run ID");
    }
    return Map.of("deletedPlans", deleted, "runId", run);
  }
}

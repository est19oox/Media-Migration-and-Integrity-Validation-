package mmiv.reporting.integration;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ReportAuthorization {
  private final RestClient metadata;

  public ReportAuthorization(
      @Value("${mmiv.metadata.url:http://discovery-service}") String url,
      mmiv.resilience.EurekaHttpClients.EurekaRestClientFactory clients) {
    metadata = clients.create("metadata", url, Duration.ofSeconds(5));
  }

  public void require(UUID id) {
    if (!(SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken token))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    try {
      metadata
          .get()
          .uri("/api/metadata/plans/{id}", id)
          .header("Authorization", "Bearer " + token.getToken().getTokenValue())
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Report permission required");
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Metadata unavailable");
    }
  }
}

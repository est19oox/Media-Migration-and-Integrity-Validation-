package mmiv.integration;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TransferClient {
  private final RestClient client;

  public TransferClient(
      @Value("${mmiv.transfer.url:http://transfer-service}") String url,
      mmiv.resilience.EurekaHttpClients.EurekaRestClientFactory clients) {
    client = clients.create("transfer", url, Duration.ofMinutes(3));
  }

  public Object transfer(UUID metadataId, String token) {
    try {
      return client
          .post()
          .uri("/api/v1/transfers")
          .headers(
              headers -> {
                headers.setBearerAuth(token);
                if (org.springframework.web.context.request.RequestContextHolder
                        .getRequestAttributes()
                    instanceof
                    org.springframework.web.context.request.ServletRequestAttributes attributes) {
                  String run = attributes.getRequest().getHeader("X-MMIV-Verification-Run");
                  if (run != null)
                    headers.set("X-MMIV-Verification-Run", UUID.fromString(run).toString());
                }
              })
          .body(Map.of("metadataId", metadataId))
          .retrieve()
          .body(Object.class);
    } catch (org.springframework.web.client.HttpClientErrorException rejected) {
      // Preserve caller/business status; a denied plan is not a backend outage.
      throw new ResponseStatusException(rejected.getStatusCode(), "Transfer request rejected");
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Transfer service unavailable");
    }
  }
}

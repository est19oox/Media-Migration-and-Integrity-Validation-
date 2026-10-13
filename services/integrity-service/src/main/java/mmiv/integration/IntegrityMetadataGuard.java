package mmiv.integration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import mmiv.dto.ValidationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class IntegrityMetadataGuard {
  private final RestClient client;

  public IntegrityMetadataGuard(
      @Value("${mmiv.metadata.url:http://discovery-service:8085}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    client =
        RestClient.builder()
            .requestInterceptor(mmiv.resilience.DependencyCircuits.http("metadata", url))
            .baseUrl(url)
            .requestFactory(factory)
            .build();
  }

  public Plan require(
      Jwt jwt, UUID metadataId, UUID migrationId, UUID fileId, ValidationRequest request) {
    if (jwt == null || metadataId == null || migrationId == null || fileId == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Metadata correlation is required");
    try {
      Plan plan =
          client
              .get()
              .uri("/api/metadata/plans/{id}/resolve", metadataId)
              .header("Authorization", "Bearer " + jwt.getTokenValue())
              .retrieve()
              .body(Plan.class);
      if (plan == null
          || !jwt.getSubject().equals(plan.ownerId())
          || !metadataId.equals(plan.metadataId())
          || !migrationId.equals(plan.migrationId())
          || !fileId.equals(plan.fileId())
          || !Objects.equals(request.bucket(), plan.bucket())
          || !Objects.equals(request.objectKey(), plan.destinationKey()))
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Object does not match metadata assignment");
      return plan;
    } catch (HttpClientErrorException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Metadata permission required");
    } catch (RestClientException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Metadata service unavailable");
    }
  }

  public record Plan(
      UUID metadataId,
      UUID migrationId,
      UUID fileId,
      String ownerId,
      String bucket,
      String destinationKey) {}
}

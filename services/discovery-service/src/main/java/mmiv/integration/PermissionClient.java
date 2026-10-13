package mmiv.integration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PermissionClient {
  private final RestClient client;

  public PermissionClient(@Value("${mmiv.access.url:http://user-access-service:8083}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    client =
        RestClient.builder()
            .requestInterceptor(mmiv.resilience.DependencyCircuits.http("user-access", url))
            .baseUrl(url)
            .requestFactory(factory)
            .build();
  }

  public void require(String token, String userId, String destination, String action) {
    try {
      var result =
          client
              .post()
              .uri("/api/access/check")
              .header("Authorization", "Bearer " + token)
              .body(Map.of("userId", userId, "destination", destination, "action", action))
              .retrieve()
              .body(Decision.class);
      if (result == null || !result.authorised())
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Destination permission required");
    } catch (org.springframework.web.client.HttpClientErrorException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Destination permission required");
    } catch (RestClientException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Permission service unavailable");
    }
  }

  public record Decision(boolean authorised, String message) {}

  public void requireAdmin(String token) {
    try {
      client
          .get()
          .uri("/api/admin/me")
          .header("Authorization", "Bearer " + token)
          .retrieve()
          .toBodilessEntity();
    } catch (org.springframework.web.client.HttpClientErrorException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required");
    } catch (RestClientException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Account service unavailable");
    }
  }

  public void requireFolder(String token, String folder) {
    try {
      client
          .get()
          .uri("/api/access/folders/{name}", folder)
          .header("Authorization", "Bearer " + token)
          .retrieve()
          .toBodilessEntity();
    } catch (org.springframework.web.client.HttpClientErrorException e) {
      throw new ResponseStatusException(e.getStatusCode(), "Folder unavailable");
    } catch (RestClientException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Folder service unavailable");
    }
  }

  public void requireActive(String token) {
    try {
      client
          .get()
          .uri("/api/access/me")
          .header("Authorization", "Bearer " + token)
          .retrieve()
          .toBodilessEntity();
    } catch (org.springframework.web.client.HttpClientErrorException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account unavailable");
    } catch (RestClientException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Account service unavailable");
    }
  }
}

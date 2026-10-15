package mmiv.reporting.integration;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CurrentAdminAuthorization {
  private final RestClient access;

  public CurrentAdminAuthorization(
      @Value("${mmiv.access.url:http://user-access-service:8083}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    access = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  public void require(String token) {
    try {
      access
          .get()
          .uri("/api/admin/me")
          .header("Authorization", "Bearer " + token)
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException exception) {
      if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current admin access required");
      }
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "User Access authorization failed");
    } catch (RestClientException exception) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "User Access authorization unavailable");
    }
  }
}

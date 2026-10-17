package mmiv.transfer.integration;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PortalClient {
  private final RestClient access;
  private final java.net.URI accessBase;

  public PortalClient(
      @Value("${mmiv.access.url:http://user-access-service}") String accessUrl,
      mmiv.resilience.EurekaHttpClients.EurekaRestClientFactory clients) {
    access = clients.create("user-access", accessUrl, Duration.ofSeconds(20));
    accessBase = java.net.URI.create(accessUrl);
  }

  public Object access(HttpMethod method, String path, Object body) {
    return call(access, method, path, body);
  }

  public void admin() {
    access(HttpMethod.GET, "/api/admin/me", null);
  }

  private Object call(RestClient client, HttpMethod method, String path, Object body) {
    try {
      var request =
          client
              .method(method)
              .uri(accessBase.resolve(path))
              .header("Authorization", "Bearer " + MetadataClient.currentJwt().getTokenValue());
      if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
      return request.retrieve().body(Object.class);
    } catch (HttpClientErrorException e) {
      throw new ResponseStatusException(
          e.getStatusCode(), "Request denied or resource unavailable");
    } catch (RestClientException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable; please retry");
    }
  }

  public static String encode(String value) {
    return org.springframework.web.util.UriUtils.encode(
        value, java.nio.charset.StandardCharsets.UTF_8);
  }
}

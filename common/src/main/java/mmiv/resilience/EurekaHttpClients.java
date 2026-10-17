package mmiv.resilience;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/** Creates HTTP clients that resolve logical service names through Eureka and Spring Cloud. */
@Configuration
public class EurekaHttpClients {
  @Bean
  @LoadBalanced
  @Qualifier("eurekaRestClientBuilder")
  RestClient.Builder eurekaRestClientBuilder() {
    return RestClient.builder();
  }

  @Bean
  @LoadBalanced
  @Qualifier("eurekaRestTemplate")
  RestTemplate eurekaRestTemplate() {
    var requests =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    requests.setReadTimeout(Duration.ofSeconds(10));
    return new RestTemplate(requests);
  }

  public static boolean isDirect(String url) {
    URI endpoint = URI.create(url);
    return endpoint.getPort() >= 0 || "localhost".equals(endpoint.getHost());
  }

  @Bean
  EurekaRestClientFactory eurekaRestClientFactory(
      @Qualifier("eurekaRestClientBuilder") RestClient.Builder loadBalancedBuilder) {
    return new EurekaRestClientFactory(loadBalancedBuilder);
  }

  public static final class EurekaRestClientFactory {
    private final RestClient.Builder loadBalancedBuilder;

    public EurekaRestClientFactory(RestClient.Builder loadBalancedBuilder) {
      this.loadBalancedBuilder = loadBalancedBuilder;
    }

    public RestClient create(String dependency, String url, Duration readTimeout) {
      URI endpoint = URI.create(url);
      // Explicit localhost/port URLs are retained for isolated tests and operator diagnostics.
      boolean direct = isDirect(url);
      RestClient.Builder builder = direct ? RestClient.builder() : loadBalancedBuilder.clone();
      var requests =
          new JdkClientHttpRequestFactory(
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
      requests.setReadTimeout(readTimeout);
      return builder
          .baseUrl(url)
          .requestFactory(requests)
          .requestInterceptor(DependencyCircuits.http(dependency, "eureka:" + endpoint.getHost()))
          .build();
    }
  }
}

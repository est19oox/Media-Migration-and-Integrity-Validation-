package mmiv;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

/** Eureka must use a direct HTTP client, not a load-balanced business RestClient builder. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"eureka.client.fetch-registry=false", "eureka.client.register-with-eureka=true"})
class EurekaRegistrationTest {
  private static final CountDownLatch registered = new CountDownLatch(1);
  private static final HttpServer registry = startRegistry();

  @MockitoBean S3Client s3;

  private static HttpServer startRegistry() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/eureka/apps/INTEGRITY-SERVICE",
          exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
              registered.countDown();
              exchange.sendResponseHeaders(204, -1);
            } else {
              exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
          });
      server.start();
      return server;
    } catch (java.io.IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @DynamicPropertySource
  static void eurekaEndpoint(DynamicPropertyRegistry properties) {
    properties.add(
        "eureka.client.service-url.defaultZone",
        () -> "http://127.0.0.1:" + registry.getAddress().getPort() + "/eureka/");
  }

  @AfterAll
  static void stopRegistry() {
    registry.stop(0);
  }

  @Test
  void registrationPostReachesEurekaWithoutDiscoveringEurekaFirst() throws InterruptedException {
    assertTrue(registered.await(20, TimeUnit.SECONDS));
  }
}

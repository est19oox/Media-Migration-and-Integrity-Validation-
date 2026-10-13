package mmiv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import mmiv.reporting.report.MigrationReportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "management.health.mongodb.enabled=false",
      "management.health.rabbit.enabled=false"
    })
class ApplicationTest {

  @LocalServerPort private int port;

  @MockitoBean private MigrationReportRepository reportRepository;

  @Test
  void exposesActuatorHealth() throws Exception {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
            .GET()
            .build();

    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
    assertTrue(response.body().contains("\"status\":\"UP\""));
  }
}

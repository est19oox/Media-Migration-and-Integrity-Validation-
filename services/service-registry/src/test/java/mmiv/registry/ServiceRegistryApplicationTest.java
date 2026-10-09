package mmiv.registry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ServiceRegistryApplicationTest {
    @Autowired TestRestTemplate http;

    @Test void servesHealthAndRegistryApi() {
        assertEquals(HttpStatus.OK, http.getForEntity("/actuator/health", String.class).getStatusCode());
        var headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        var response = http.exchange("/eureka/apps", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("applications"));
    }
}

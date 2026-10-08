package mmiv;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "eureka.client.enabled=false")
class ApplicationTest {

    @Test
    void contextLoads() {
    }
}

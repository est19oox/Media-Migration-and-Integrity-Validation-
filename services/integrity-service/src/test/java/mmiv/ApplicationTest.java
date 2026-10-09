package mmiv;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "eureka.client.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:integrity;MODE=Oracle;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS INTEGRITY_SERVICE",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ApplicationTest {
    @MockitoBean S3Client s3Client;
    @Test void contextLoads() {}
}

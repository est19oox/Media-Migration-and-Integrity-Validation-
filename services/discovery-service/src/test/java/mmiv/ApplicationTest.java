
package mmiv;

import mmiv.repository.FileMetadataRepository;
import mmiv.repository.ValidationLogRepository;
import mmiv.runner.DiscoveryRunner;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "discovery.root=/tmp",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "spring.main.web-application-type=none",
        "eureka.client.enabled=false"
})
class ApplicationTest {

    // Mock the metadata repository to avoid Oracle dependency.
    @MockitoBean
    private FileMetadataRepository fileMetadataRepository;

    // Mock the validation log repository to avoid Oracle dependency.
    @MockitoBean
    private ValidationLogRepository validationLogRepository;

    // Prevent DiscoveryRunner from scanning /tmp during context startup.
    @MockitoBean
    private DiscoveryRunner discoveryRunner;

    @Test
    void contextLoads() {
        // Verify that the Spring ApplicationContext starts successfully.
    }
}

package mmiv;

import mmiv.repository.FileMetadataRepository;
import mmiv.repository.ValidationLogRepository;
import mmiv.runner.DiscoveryRunner;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies that the whole application context starts, including the security
 * configuration. The data source is excluded and the Oracle repositories are
 * mocked by the {@code test} profile, so no database is required.
 */
@SpringBootTest
@ActiveProfiles("test")
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

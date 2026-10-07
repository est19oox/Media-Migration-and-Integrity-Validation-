package mmiv;

import mmiv.repository.FileMetadataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "discovery.root=/tmp",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
    }
)
class ApplicationTest {

    @MockitoBean
    private FileMetadataRepository fileMetadataRepository;

    @Test
    void contextLoads() {
    }
}
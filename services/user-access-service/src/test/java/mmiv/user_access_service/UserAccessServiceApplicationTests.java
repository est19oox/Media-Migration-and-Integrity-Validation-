
package mmiv.user_access_service;

import mmiv.user_access_service.config.TestJwtConfig;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class UserAccessServiceApplicationTests {

	@Test
	void contextLoads() {
	}
}

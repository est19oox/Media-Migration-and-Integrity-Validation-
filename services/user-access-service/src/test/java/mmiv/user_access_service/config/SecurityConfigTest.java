package mmiv.user_access_service.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class SecurityConfigTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void shouldRejectAccessCheckWithoutJwt() throws Exception {
    mockMvc
        .perform(
            post("/api/access/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "userId": "U001",
                          "destination": "DEST_A",
                          "action": "READ"
                        }
                        """))
        .andExpect(status().isUnauthorized());
  }
}

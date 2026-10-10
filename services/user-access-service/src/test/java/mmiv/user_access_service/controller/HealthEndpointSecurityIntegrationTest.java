package mmiv.user_access_service.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import mmiv.user_access_service.config.TestJwtConfig;
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
class HealthEndpointSecurityIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void apiHealthIsPublicAndReturnsOnlyStaticStatus() throws Exception {
    mockMvc
        .perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(content().string("User Access Service is running"));
  }

  @Test
  void actuatorHealthIsPublic() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void accessCheckStillRequiresJwt() throws Exception {
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

  @Test
  void unspecifiedApplicationEndpointRemainsDenied() throws Exception {
    mockMvc.perform(get("/api/not-a-real-endpoint")).andExpect(status().isUnauthorized());
  }
}

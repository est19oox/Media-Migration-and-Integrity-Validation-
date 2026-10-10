package mmiv.user_access_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import mmiv.user_access_service.dto.AccessCheckResponse;
import mmiv.user_access_service.service.AccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccessController.class)
@AutoConfigureMockMvc(addFilters = false)
class AccessControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AccessService accessService;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void shouldReturnAuthorisedWhenAccessIsGranted() throws Exception {
    authenticateAs("U001");

    when(accessService.checkAccess(any()))
        .thenReturn(new AccessCheckResponse(true, "Access granted"));

    mockMvc
        .perform(
            post("/api/access/check")
                .contentType("application/json")
                .content(
                    """
                                {
                                  "userId": "U001",
                                  "destination": "mmiv-bucket-a",
                                  "action": "WRITE"
                                }
                                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(true))
        .andExpect(jsonPath("$.reason").value("Access granted"));
  }

  @Test
  void shouldReturnDeniedWhenAccessIsRejected() throws Exception {
    authenticateAs("U002");

    when(accessService.checkAccess(any()))
        .thenReturn(new AccessCheckResponse(false, "Insufficient permission"));

    mockMvc
        .perform(
            post("/api/access/check")
                .contentType("application/json")
                .content(
                    """
                                                            {
                                                              "userId": "U002",
                                                              "destination": "mmiv-bucket-a",
                                                              "action": "WRITE"
                                                            }
                                                            """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(false))
        .andExpect(jsonPath("$.reason").value("Insufficient permission"));
  }

  private void authenticateAs(String subject) {
    Jwt jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .subject(subject)
            .issuedAt(java.time.Instant.now())
            .expiresAt(java.time.Instant.now().plusSeconds(300))
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }

  @Test
  void shouldReturnBadRequestWhenUserIdIsMissing() throws Exception {

    mockMvc
        .perform(
            post("/api/access/check")
                .contentType("application/json")
                .content(
                    """
                                                            {
                                                              "destination": "mmiv-bucket-a",
                                                              "action": "WRITE"
                                                            }
                                                            """))
        .andExpect(status().isBadRequest());
  }
}

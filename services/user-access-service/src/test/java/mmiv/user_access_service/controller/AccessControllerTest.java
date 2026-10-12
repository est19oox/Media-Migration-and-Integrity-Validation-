package mmiv.user_access_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import mmiv.user_access_service.dto.AccessCheckResponse;
import mmiv.user_access_service.service.AccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(AccessController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AccessControllerTest.SecurityPrincipalConfiguration.class)
class AccessControllerTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean mmiv.events.EventPublisher auditPublisher;


  @Autowired private MockMvc mockMvc;

  @MockitoBean private AccessService accessService;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityPrincipalConfiguration implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
      argumentResolvers.add(new AuthenticationPrincipalArgumentResolver());
    }
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

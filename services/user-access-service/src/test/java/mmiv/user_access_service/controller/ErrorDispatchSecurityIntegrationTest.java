package mmiv.user_access_service.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.DispatcherType;
import mmiv.user_access_service.config.TestJwtConfig;
import mmiv.user_access_service.service.AccessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class ErrorDispatchSecurityIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AccessService accessService;

  @Test
  void normalUnauthenticatedAccessCheckReturnsUnauthorized() throws Exception {
    mockMvc.perform(accessCheck()).andExpect(status().isUnauthorized());

    verifyNoInteractions(accessService);
  }

  @Test
  void unknownApplicationEndpointRemainsDenied() throws Exception {
    mockMvc.perform(get("/api/not-a-real-endpoint")).andExpect(status().isUnauthorized());
  }

  @Test
  void errorDispatchCannotReturnProtectedAuthorizationData() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                accessCheck()
                    .with(
                        request -> {
                          request.setDispatcherType(DispatcherType.ERROR);
                          return request;
                        }))
            .andExpect(status().isForbidden())
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertFalse(body.contains("\"authorised\":true"));
    assertFalse(body.contains("\"authorised\":false"));
    verifyNoInteractions(accessService);
  }

  @Test
  void clientHeadersOrParametersCannotImpersonateErrorDispatch() throws Exception {
    mockMvc
        .perform(accessCheck().header("DispatcherType", "ERROR").param("dispatcherType", "ERROR"))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(accessService);
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder accessCheck() {
    return post("/api/access/check")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {
              "userId": "U001",
              "destination": "DEST_A",
              "action": "READ"
            }
            """);
  }
}

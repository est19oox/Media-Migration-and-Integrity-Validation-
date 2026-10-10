package mmiv.user_access_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import mmiv.user_access_service.dto.AccessCheckResponse;
import mmiv.user_access_service.service.AccessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccessController.class)
class AccessControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AccessService accessService;

  @Test
  void shouldReturnAuthorisedWhenAccessIsGranted() throws Exception {

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
                          "action": "WRITE"
                        }
                        """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(true))
        .andExpect(jsonPath("$.reason").value("Access granted"));
  }

  @Test
  void shouldReturnDeniedWhenAccessIsRejected() throws Exception {

    when(accessService.checkAccess(any()))
        .thenReturn(new AccessCheckResponse(false, "Permission denied"));

    mockMvc
        .perform(
            post("/api/access/check")
                .contentType("application/json")
                .content(
                    """
                        {
                          "userId": "U002",
                          "action": "WRITE"
                        }
                        """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(false))
        .andExpect(jsonPath("$.reason").value("Permission denied"));
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
                          "action": "WRITE"
                        }
                        """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldReturnBadRequestWhenActionIsMissing() throws Exception {

    mockMvc
        .perform(
            post("/api/access/check")
                .contentType("application/json")
                .content(
                    """
                        {
                          "userId": "U001"
                        }
                        """))
        .andExpect(status().isBadRequest());
  }
}

package mmiv.user_access_service.controller;

import mmiv.user_access_service.dto.AccessCheckResponse;
import mmiv.user_access_service.service.AccessService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccessController.class)
class AccessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessService accessService;

    @Test
    void shouldReturnAuthorisedWhenAccessIsGranted() throws Exception {

        when(accessService.checkAccess(any()))
                .thenReturn(
                        new AccessCheckResponse(
                                true,
                                "Access granted"));

        mockMvc.perform(
                post("/api/access/check")
                        .contentType("application/json")
                        .content("""
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

            when(accessService.checkAccess(any()))
                            .thenReturn(
                                            new AccessCheckResponse(
                                                            false,
                                                            "Insufficient permission"));

            mockMvc.perform(
                            post("/api/access/check")
                                            .contentType("application/json")
                                            .content("""
                                                            {
                                                              "userId": "U002",
                                                              "destination": "mmiv-bucket-a",
                                                              "action": "WRITE"
                                                            }
                                                            """))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.authorised").value(false))
                            .andExpect(jsonPath("$.reason")
                                            .value("Insufficient permission"));
    }
    
    @Test
    void shouldReturnBadRequestWhenUserIdIsMissing() throws Exception {

            mockMvc.perform(
                            post("/api/access/check")
                                            .contentType("application/json")
                                            .content("""
                                                            {
                                                              "destination": "mmiv-bucket-a",
                                                              "action": "WRITE"
                                                            }
                                                            """))
                            .andExpect(status().isBadRequest());
    }
}
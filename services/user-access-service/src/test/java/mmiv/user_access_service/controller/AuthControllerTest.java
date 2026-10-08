
package mmiv.user_access_service.controller;

import mmiv.user_access_service.service.AuthenticationService;
import mmiv.user_access_service.service.InvalidCredentialsException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @Test
    void shouldReturnJwtForValidLogin() throws Exception {
        when(authenticationService.login("alice", "correct-password"))
                .thenReturn("signed-jwt-token");

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                        {
                          "username": "alice",
                          "password": "correct-password"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken")
                        .value("signed-jwt-token"))
                .andExpect(jsonPath("$.tokenType")
                        .value("Bearer"));
    }

    @Test
    void shouldReturnUnauthorizedForInvalidCredentials() throws Exception {
        when(authenticationService.login("alice", "wrong-password"))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                        {
                          "username": "alice",
                          "password": "wrong-password"
                        }
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnBadRequestWhenUsernameIsMissing() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                        {
                          "password": "password123"
                        }
                        """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authenticationService);
    }

    @Test
    void shouldReturnBadRequestWhenPasswordIsMissing() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                        {
                          "username": "alice"
                        }
                        """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authenticationService);
    }
}

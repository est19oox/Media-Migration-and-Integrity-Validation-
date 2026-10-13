package mmiv.user_access_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import mmiv.user_access_service.dto.SignupResponse;
import mmiv.user_access_service.service.DuplicateUsernameException;
import mmiv.user_access_service.service.RegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SignupControllerTest {
  RegistrationService registration;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    registration = mock(RegistrationService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new SignupController(registration))
            .setControllerAdvice(new AuthExceptionHandler())
            .build();
  }

  @Test
  void signupReturnsCreatedAndNoPasswordOrToken() throws Exception {
    when(registration.register(any()))
        .thenReturn(new SignupResponse("U001", "alice", "Account created."));
    mvc.perform(
            post("/api/auth/signup")
                .contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value("alice"))
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.accessToken").doesNotExist());
  }

  @Test
  void invalidRequestNeverCreatesAnAccount() throws Exception {
    mvc.perform(
            post("/api/auth/signup")
                .contentType("application/json")
                .content("{\"username\":\"ab\",\"password\":\"short\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(registration);
  }

  @Test
  void duplicateReturnsConflict() throws Exception {
    when(registration.register(any())).thenThrow(new DuplicateUsernameException());
    mvc.perform(
            post("/api/auth/signup")
                .contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("Username is already taken"));
  }
}

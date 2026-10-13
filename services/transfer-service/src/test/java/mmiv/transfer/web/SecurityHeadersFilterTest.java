package mmiv.transfer.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class SecurityHeadersFilterTest {
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .addFilters(new SecurityHeadersFilter())
            .build();
  }

  @Test
  void addsHeadersRequiredByTheDastPolicy() throws Exception {
    mvc.perform(get("/probe"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"));
  }

  @RestController
  private static class ProbeController {
    @GetMapping("/probe")
    String probe() {
      return "ok";
    }
  }
}

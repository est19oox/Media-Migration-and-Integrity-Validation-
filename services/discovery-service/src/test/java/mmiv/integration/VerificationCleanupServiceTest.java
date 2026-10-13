package mmiv.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class VerificationCleanupServiceTest {
  @Test
  void peerFailureRetainsPlansForRetryAndNeverDeletesUnrelatedRows() throws Exception {
    var repository = mock(MetadataPlanRepository.class);
    var permissions = mock(PermissionClient.class);
    var run = UUID.randomUUID();
    var paths = new ArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestURI().getPath());
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    try {
      var service = new VerificationCleanupService(repository, permissions, "landing", url, url);
      var jwt = Jwt.withTokenValue("fixture").header("alg", "RS256").subject("owner").build();
      assertThrows(
          org.springframework.web.server.ResponseStatusException.class,
          () -> service.clean(jwt, run));
      verify(repository).verificationPlans("owner", run);
      verify(repository).closeRun("owner", run);
      verify(repository, never()).deleteVerificationPlans(anyString(), any(UUID.class));
      assertEquals(List.of("/api/v1/verification/" + run + "/cleanup"), paths);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void deniedPermissionCannotCloseRunOrDeleteData() {
    var repository = mock(MetadataPlanRepository.class);
    var permissions = mock(PermissionClient.class);
    doThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN))
        .when(permissions)
        .requireActive("fixture");
    var service =
        new VerificationCleanupService(
            repository, permissions, "landing", "http://127.0.0.1:1", "http://127.0.0.1:1");
    var jwt = Jwt.withTokenValue("fixture").header("alg", "RS256").subject("owner").build();
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.clean(jwt, UUID.randomUUID()));
    verifyNoInteractions(repository);
  }
}

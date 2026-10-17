package mmiv.reporting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.UUID;
import mmiv.reporting.integration.CurrentAdminAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class AdminReportControllerTest {
  @Test
  void adminListRequiresSuccessfulCurrentAdminCheck() {
    ReportQueryService queries = mock(ReportQueryService.class);
    CurrentAdminAuthorization authorization = mock(CurrentAdminAuthorization.class);
    AdminReportController controller = new AdminReportController(queries, authorization);
    when(queries.all(any(), any(), any())).thenReturn(Page.empty());
    Jwt jwt = jwt();

    controller.reports(jwt, null, null, 0, 20);

    var order = inOrder(authorization, queries);
    order.verify(authorization).require(jwt.getTokenValue());
    order.verify(queries).all(any(), any(), any());
  }

  @Test
  void adminFileFilterRequiresAuthorizationBeforeQuerying() {
    ReportQueryService queries = mock(ReportQueryService.class);
    CurrentAdminAuthorization authorization = mock(CurrentAdminAuthorization.class);
    AdminReportController controller = new AdminReportController(queries, authorization);
    UUID migrationId = UUID.fromString("c49f2f8f-ad97-45a8-8867-f3ca8a971d80");
    when(queries.anyFiles(any(), org.mockito.ArgumentMatchers.eq(true), any()))
        .thenReturn(Page.empty());
    Jwt jwt = jwt();

    controller.files(jwt, migrationId, true, 0, 20);

    var order = inOrder(authorization, queries);
    order.verify(authorization).require(jwt.getTokenValue());
    order
        .verify(queries)
        .anyFiles(
            org.mockito.ArgumentMatchers.eq(migrationId),
            org.mockito.ArgumentMatchers.eq(true),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void staleAdminClaimFailsWhenUserAccessReturns403() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/admin/me",
        exchange -> {
          exchange.sendResponseHeaders(403, -1);
          exchange.close();
        });
    server.start();
    try {
      CurrentAdminAuthorization authorization =
          new CurrentAdminAuthorization("http://127.0.0.1:" + server.getAddress().getPort());

      ResponseStatusException failure =
          assertThrows(ResponseStatusException.class, () -> authorization.require("stale-token"));

      assertEquals(403, failure.getStatusCode().value());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void adminDependencyFailureReturns503() throws Exception {
    int unusedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      unusedPort = socket.getLocalPort();
    }
    CurrentAdminAuthorization authorization =
        new CurrentAdminAuthorization("http://127.0.0.1:" + unusedPort);

    ResponseStatusException failure =
        assertThrows(ResponseStatusException.class, () -> authorization.require("valid-token"));

    assertEquals(503, failure.getStatusCode().value());
  }

  @Test
  void ordinaryControllerDoesNotKnowAboutAdministratorAuthorization() {
    CurrentAdminAuthorization authorization = mock(CurrentAdminAuthorization.class);
    ReportQueryService queries = mock(ReportQueryService.class);
    when(queries.mine(any(), any(), any())).thenReturn(Page.empty());

    new UserReportController(queries).mine(jwt(), null, 0, 20);

    verifyNoInteractions(authorization);
  }

  private Jwt jwt() {
    return Jwt.withTokenValue("verified-admin-token")
        .header("alg", "RS256")
        .subject("admin-1")
        .issuedAt(Instant.now().minusSeconds(30))
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
  }
}

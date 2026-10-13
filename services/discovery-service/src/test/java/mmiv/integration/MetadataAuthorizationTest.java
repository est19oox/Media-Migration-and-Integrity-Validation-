package mmiv.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import mmiv.events.EventPublisher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class MetadataAuthorizationTest {
  @TempDir Path root;
  PermissionClient permissions = mock(PermissionClient.class);
  MetadataPlanRepository plans = mock(MetadataPlanRepository.class);
  EventPublisher events = mock(EventPublisher.class);
  Jwt jwt = Jwt.withTokenValue("caller-token").header("alg", "RS256").subject("caller").build();

  MetadataService service() {
    return new MetadataService(permissions, plans, events, root.toString(), "landing", "bucket");
  }

  @Test
  void deniedWriteCannotPersistPlanOrEmitAcceptedDiscovery() {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
        .when(permissions)
        .require("caller-token", "caller", "landing", "WRITE");
    assertThrows(
        ResponseStatusException.class,
        () ->
            service()
                .create(jwt, new MetadataService.CreatePlan("landing", null, "test.txt", 5), true));
    verifyNoInteractions(plans, events);
  }

  @Test
  void assignsDestinationAndUsesDatabasePermissionRatherThanJwtClaim() throws Exception {
    Files.writeString(root.resolve("test.txt"), "hello");
    var plan =
        service()
            .create(jwt, new MetadataService.CreatePlan("landing", "test.txt", null, 0), false);
    assertEquals("bucket", plan.bucket());
    assertEquals("landing/migrations/" + plan.metadataId() + "/test.txt", plan.destinationKey());
    verify(permissions).require("caller-token", "caller", "landing", "WRITE");
    verify(plans).saveNamed(plan, null);
    verify(events, times(3))
        .migration(
            anyString(), anyString(), eq(plan.migrationId()), nullable(UUID.class), anyMap());
  }

  @Test
  void rejectsUnconfiguredDestinationAndPathTraversalBeforeSaving() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            service()
                .create(
                    jwt, new MetadataService.CreatePlan("../other", null, "test.txt", 5), true));
    assertThrows(
        ResponseStatusException.class,
        () ->
            service()
                .create(
                    jwt,
                    new MetadataService.CreatePlan("landing", "../outside.txt", null, 0),
                    false));
    verifyNoInteractions(plans, events);
  }

  @Test
  void resolveRequiresOwnerAndRechecksCurrentWritePermission() {
    var id = UUID.randomUUID();
    when(plans.find(id, "caller")).thenReturn(Optional.empty());
    assertThrows(ResponseStatusException.class, () -> service().get(jwt, id, "WRITE"));
    verify(plans).find(id, "caller");
    verifyNoInteractions(permissions);
  }
}

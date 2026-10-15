package mmiv.reporting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import mmiv.reporting.audit.AuditEventRepository;
import mmiv.reporting.report.MigrationReportDocument;
import mmiv.reporting.report.MigrationReportRepository;
import mmiv.reporting.report.MigrationReportStatus;
import mmiv.reporting.report.MigrationSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class UserReportControllerTest {
  private static final UUID MIGRATION_ID = UUID.fromString("c49f2f8f-ad97-45a8-8867-f3ca8a971d80");

  @Mock private MigrationReportRepository reports;
  @Mock private AuditEventRepository events;

  private ReportQueryService queries;
  private UserReportController controller;

  @BeforeEach
  void setUp() {
    queries = new ReportQueryService(reports, events);
    controller = new UserReportController(queries);
  }

  @Test
  void mineQueriesRepositoryByInitiatedByAndNeverFindAll() {
    when(reports.findByInitiatedBy(org.mockito.ArgumentMatchers.eq("alice"), any()))
        .thenReturn(Page.empty());

    controller.mine(jwt("alice"), null, 0, 20);

    verify(reports).findByInitiatedBy(org.mockito.ArgumentMatchers.eq("alice"), any());
    verify(reports, never()).findAll(any(Pageable.class));
  }

  @Test
  void mineCanFilterStatusAndUsesStartedAtDescending() {
    when(reports.findByInitiatedByAndStatus(
            org.mockito.ArgumentMatchers.eq("alice"),
            org.mockito.ArgumentMatchers.eq(MigrationReportStatus.COMPLETED),
            any()))
        .thenReturn(Page.empty());

    controller.mine(jwt("alice"), MigrationReportStatus.COMPLETED, 2, 25);

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(reports)
        .findByInitiatedByAndStatus(
            org.mockito.ArgumentMatchers.eq("alice"),
            org.mockito.ArgumentMatchers.eq(MigrationReportStatus.COMPLETED),
            pageable.capture());
    assertEquals(2, pageable.getValue().getPageNumber());
    assertEquals(25, pageable.getValue().getPageSize());
    assertEquals(
        Sort.Direction.DESC, pageable.getValue().getSort().getOrderFor("startedAt").getDirection());
  }

  @Test
  void reportWithNullOrBlankInitiatedByIsExcludedFromMine() {
    when(reports.findByInitiatedBy(org.mockito.ArgumentMatchers.eq("alice"), any()))
        .thenReturn(Page.empty());

    Page<ReportResponse> result = controller.mine(jwt("alice"), null, 0, 20);

    assertEquals(0, result.getTotalElements());
    verify(reports).findByInitiatedBy(org.mockito.ArgumentMatchers.eq("alice"), any());
    verify(reports, never()).findAll(any(Pageable.class));
  }

  @Test
  void ownerCanReadSummaryAndFiles() {
    MigrationReportDocument report = report("alice");
    UUID fileId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    report.getOrCreateFile(fileId).recordDiscoveryAccepted();
    when(reports.findById(MIGRATION_ID.toString())).thenReturn(Optional.of(report));

    assertEquals(
        MIGRATION_ID.toString(), controller.report(jwt("alice"), MIGRATION_ID).migrationId());
    assertEquals(
        fileId.toString(),
        controller.files(jwt("alice"), MIGRATION_ID, 0, 20).getContent().getFirst().fileId());
  }

  @Test
  void otherUserReceives404ForSummaryAndFiles() {
    when(reports.findById(MIGRATION_ID.toString())).thenReturn(Optional.of(report("alice")));

    assertThrows(ReportNotFoundException.class, () -> controller.report(jwt("bob"), MIGRATION_ID));
    assertThrows(
        ReportNotFoundException.class, () -> controller.files(jwt("bob"), MIGRATION_ID, 0, 20));
  }

  @Test
  void invalidPageOrSizeReturns400() {
    IllegalArgumentException invalidPage =
        assertThrows(
            IllegalArgumentException.class, () -> controller.mine(jwt("alice"), null, -1, 20));
    IllegalArgumentException invalidSize =
        assertThrows(
            IllegalArgumentException.class, () -> controller.mine(jwt("alice"), null, 0, 201));

    ReportingExceptionHandler handler = new ReportingExceptionHandler();
    assertEquals(HttpStatus.BAD_REQUEST, handler.invalidRequest(invalidPage).getStatusCode());
    assertEquals(HttpStatus.BAD_REQUEST, handler.invalidRequest(invalidSize).getStatusCode());
  }

  private MigrationReportDocument report(String owner) {
    MigrationReportDocument report =
        MigrationReportDocument.processing(MIGRATION_ID, Instant.parse("2026-10-11T10:00:00Z"));
    report.recordStarted(
        Instant.parse("2026-10-11T10:00:00Z"),
        owner,
        new MigrationSource("MANIFEST", "legacy-media", "semester-1-media.csv"));
    return report;
  }

  private Jwt jwt(String subject) {
    return Jwt.withTokenValue("token-" + subject)
        .header("alg", "RS256")
        .subject(subject)
        .issuedAt(Instant.now().minusSeconds(30))
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
  }
}

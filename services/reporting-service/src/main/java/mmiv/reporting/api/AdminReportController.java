package mmiv.reporting.api;

import java.util.UUID;
import mmiv.reporting.integration.CurrentAdminAuthorization;
import mmiv.reporting.report.MigrationReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {
  private final ReportQueryService reports;
  private final CurrentAdminAuthorization authorization;

  public AdminReportController(
      ReportQueryService reports, CurrentAdminAuthorization authorization) {
    this.reports = reports;
    this.authorization = authorization;
  }

  @GetMapping
  public Page<ReportResponse> reports(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) MigrationReportStatus status,
      @RequestParam(required = false) String initiatedBy,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    authorization.require(jwt.getTokenValue());
    return reports.all(status, initiatedBy, PageRequest.of(page, size));
  }

  @GetMapping("/{migrationId}")
  public ReportResponse report(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID migrationId) {
    authorization.require(jwt.getTokenValue());
    return reports.any(migrationId);
  }

  @GetMapping("/{migrationId}/files")
  public Page<ReportResponse.FileResult> files(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID migrationId,
      @RequestParam(defaultValue = "false") boolean failedOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    authorization.require(jwt.getTokenValue());
    return reports.anyFiles(migrationId, failedOnly, PageRequest.of(page, size));
  }

  @GetMapping("/{migrationId}/events")
  public Page<ReportResponse.AuditEvent> events(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID migrationId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    authorization.require(jwt.getTokenValue());
    return reports.events(migrationId, PageRequest.of(page, size));
  }
}

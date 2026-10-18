package mmiv.reporting.api;

import java.util.UUID;
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
@RequestMapping("/api/reports")
public class UserReportController {
  private final ReportQueryService reports;

  public UserReportController(ReportQueryService reports) {
    this.reports = reports;
  }

  @GetMapping("/mine")
  public Page<ReportResponse> mine(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) MigrationReportStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return reports.mine(jwt.getSubject(), status, PageRequest.of(page, size));
  }

  @GetMapping("/{migrationId}")
  public ReportResponse report(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID migrationId) {
    return reports.owned(jwt.getSubject(), migrationId);
  }

  @GetMapping("/{migrationId}/files")
  public Page<ReportResponse.FileResult> files(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID migrationId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return reports.ownedFiles(jwt.getSubject(), migrationId, PageRequest.of(page, size));
  }

  @GetMapping("/{migrationId}/timeline")
  public Page<UserTimelineResponse> timeline(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID migrationId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return reports.ownedTimeline(jwt.getSubject(), migrationId, PageRequest.of(page, size));
  }
}

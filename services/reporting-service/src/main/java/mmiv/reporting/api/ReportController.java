package mmiv.reporting.api;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import mmiv.reporting.audit.AuditEventRepository;
import mmiv.reporting.report.MigrationReportDocument;
import mmiv.reporting.report.MigrationReportRepository;
import mmiv.reporting.report.MigrationReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

  private final MigrationReportRepository reportRepository;

  private final AuditEventRepository auditRepository;

  public ReportController(
      MigrationReportRepository reportRepository, AuditEventRepository auditRepository) {
    this.reportRepository = Objects.requireNonNull(reportRepository);
    this.auditRepository = Objects.requireNonNull(auditRepository);
  }

  @GetMapping("/{migrationId}")
  public ReportResponse getReport(@PathVariable UUID migrationId) {
    return ReportResponse.from(findReport(migrationId));
  }

  @GetMapping
  public Page<ReportResponse> listReports(
      @RequestParam(required = false) MigrationReportStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Pageable pageable = pageable(page, size);

    Page<MigrationReportDocument> reports =
        status == null
            ? reportRepository.findAll(pageable)
            : reportRepository.findByStatus(status, pageable);

    return reports.map(ReportResponse::from);
  }

  @GetMapping("/{migrationId}/files")
  public Page<ReportResponse.FileResult> listFiles(
      @PathVariable UUID migrationId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    MigrationReportDocument report = findReport(migrationId);
    Pageable pageable = pageable(page, size);

    List<ReportResponse.FileResult> files =
        report.getFiles().stream()
            .sorted(Comparator.comparing(value -> value.getFileId()))
            .map(ReportResponse.FileResult::from)
            .toList();

    return page(files, pageable);
  }

  @GetMapping("/{migrationId}/events")
  public Page<ReportResponse.AuditEvent> listEvents(
      @PathVariable UUID migrationId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (!reportRepository.existsById(migrationId.toString())) {
      throw new ReportNotFoundException(migrationId);
    }

    Pageable pageable = pageable(page, size);

    return auditRepository
        .findByMigrationIdOrderByOccurredAtAsc(migrationId.toString(), pageable)
        .map(ReportResponse.AuditEvent::from);
  }

  private MigrationReportDocument findReport(UUID migrationId) {
    return reportRepository
        .findById(migrationId.toString())
        .orElseThrow(() -> new ReportNotFoundException(migrationId));
  }

  private Pageable pageable(int page, int size) {
    if (page < 0) {
      throw new IllegalArgumentException("page must not be negative");
    }
    if (size < 1 || size > 200) {
      throw new IllegalArgumentException("size must be between 1 and 200");
    }

    return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));
  }

  private <T> Page<T> page(List<T> values, Pageable pageable) {
    int start = Math.toIntExact(pageable.getOffset());
    if (start >= values.size()) {
      return new PageImpl<>(List.of(), pageable, values.size());
    }

    int end = Math.min(start + pageable.getPageSize(), values.size());

    return new PageImpl<>(values.subList(start, end), pageable, values.size());
  }
}

package mmiv.reporting.report;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MigrationReportRepository
    extends MongoRepository<MigrationReportDocument, String> {

  Page<MigrationReportDocument> findByInitiatedBy(String initiatedBy, Pageable pageable);

  Page<MigrationReportDocument> findByInitiatedByAndStatus(
      String initiatedBy, MigrationReportStatus status, Pageable pageable);

  Page<MigrationReportDocument> findByStatus(MigrationReportStatus status, Pageable pageable);

  List<MigrationReportDocument> findByStatusAndStartedAtBefore(
      MigrationReportStatus status, Instant cutoff);
}

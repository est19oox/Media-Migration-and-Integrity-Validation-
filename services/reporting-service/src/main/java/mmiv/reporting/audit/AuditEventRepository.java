package mmiv.reporting.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AuditEventRepository
        extends MongoRepository<AuditEventDocument, String> {

    Page<AuditEventDocument>
    findByMigrationIdOrderByOccurredAtAsc(
            String migrationId,
            Pageable pageable
    );
}
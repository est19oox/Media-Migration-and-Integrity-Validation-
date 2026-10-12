package mmiv.reporting.report;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;

/** Minimal persistent tombstones prevent delayed/replayed events rebuilding deleted test reports. */
@Component
public class VerificationCleanupGuard {
    private final MongoTemplate mongo;
    public VerificationCleanupGuard(MongoTemplate mongo){this.mongo=mongo;}
    public boolean isDeleted(UUID migration){return mongo.exists(Query.query(Criteria.where("_id").is(migration.toString())),"verification_cleanup");}
    public void clean(UUID migration){
        mongo.upsert(Query.query(Criteria.where("_id").is(migration.toString())),new Update().setOnInsert("closedAt",java.time.Instant.now()),"verification_cleanup");
        mongo.remove(Query.query(Criteria.where("_id").is(migration.toString())),"migration_reports");
        mongo.remove(Query.query(Criteria.where("migrationId").is(migration.toString())),"audit_events");
        mongo.remove(Query.query(Criteria.where("data.migrationId").is(migration.toString())),"service_logs");
    }
}

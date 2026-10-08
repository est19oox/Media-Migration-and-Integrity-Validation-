package mmiv.repository;

import mmiv.entity.IntegrityValidationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrityValidationLogRepository
        extends JpaRepository<IntegrityValidationLog, Long> {
}
package mmiv.integration;

import java.util.Collection;
import java.util.List;
import mmiv.model.MetadataPlan;
import mmiv.repository.VerificationRunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Coordinates the plan and verification-run aggregates in one allocation transaction. */
@Service
public class MetadataAssignmentService {
  private final MetadataPlanRepository plans;
  private final VerificationRunRepository runs;

  public MetadataAssignmentService(MetadataPlanRepository plans, VerificationRunRepository runs) {
    this.plans = plans;
    this.runs = runs;
  }

  @Transactional
  public MetadataPlan allocate(MetadataPlan plan, Collection<String> occupied) {
    if (plan.requiresOpenVerificationRunForWrite()
        && !runs.lock(plan.ownerId(), plan.verificationRunId()).acceptsPlans())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Verification run is closed");
    return plans.saveNamed(plan, occupied);
  }

  @Transactional
  public List<MetadataPlan> allocateBatch(
      List<MetadataPlan> requested, Collection<String> occupied) {
    if (requested == null || requested.isEmpty())
      throw new IllegalArgumentException("plans must not be empty");
    MetadataPlan first = requested.getFirst();
    boolean inconsistent =
        requested.stream()
            .anyMatch(
                plan ->
                    !first.ownerId().equals(plan.ownerId())
                        || !first.destinationId().equals(plan.destinationId())
                        || !first.migrationId().equals(plan.migrationId())
                        || !java.util.Objects.equals(
                            first.verificationRunId(), plan.verificationRunId()));
    if (inconsistent) throw new IllegalArgumentException("batch plans must share one migration");
    if (first.requiresOpenVerificationRunForWrite()
        && !runs.lock(first.ownerId(), first.verificationRunId()).acceptsPlans())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Verification run is closed");
    return plans.saveNamedBatch(requested, occupied);
  }
}

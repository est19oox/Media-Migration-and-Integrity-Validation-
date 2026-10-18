package mmiv.integration;

import java.util.Collection;
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
}

package mmiv.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import mmiv.model.MetadataPlan;
import mmiv.model.VerificationRun;
import mmiv.repository.VerificationRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MetadataAssignmentServiceTest {
  private final MetadataPlanRepository plans = mock(MetadataPlanRepository.class);
  private final VerificationRunRepository runs = mock(VerificationRunRepository.class);
  private final MetadataAssignmentService service = new MetadataAssignmentService(plans, runs);

  @Test
  void closedVerificationRunCannotAllocateAPlan() {
    UUID run = UUID.randomUUID();
    MetadataPlan plan =
        MetadataPlan.assign("owner", "SW001", "bucket", null, "file.txt", 10, true, null, run);
    when(runs.lock("owner", run)).thenReturn(new VerificationRun("owner", run, true));

    assertThrows(ResponseStatusException.class, () -> service.allocate(plan, List.of()));
    verifyNoInteractions(plans);
  }

  @Test
  void openRunIsLockedBeforeThePlanIsSaved() {
    UUID run = UUID.randomUUID();
    MetadataPlan plan =
        MetadataPlan.assign("owner", "SW001", "bucket", null, "file.txt", 10, true, null, run);
    when(runs.lock("owner", run)).thenReturn(new VerificationRun("owner", run, false));
    when(plans.saveNamed(plan, List.of())).thenReturn(plan);

    assertSame(plan, service.allocate(plan, List.of()));
    var order = inOrder(runs, plans);
    order.verify(runs).lock("owner", run);
    order.verify(plans).saveNamed(plan, List.of());
  }
}

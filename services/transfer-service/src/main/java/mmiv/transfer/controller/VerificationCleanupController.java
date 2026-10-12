package mmiv.transfer.controller;

import java.util.UUID;
import mmiv.transfer.service.VerificationCleanupService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/verification")
public class VerificationCleanupController {
    private final VerificationCleanupService cleanup;
    public VerificationCleanupController(VerificationCleanupService cleanup){this.cleanup=cleanup;}
    @PostMapping("/{run}/cleanup") public Object cleanup(@PathVariable UUID run){return cleanup.cleanup(run);}
}

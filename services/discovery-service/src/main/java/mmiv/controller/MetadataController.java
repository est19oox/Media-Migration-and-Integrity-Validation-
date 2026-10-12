package mmiv.controller;

import java.util.UUID;
import mmiv.integration.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metadata")
public class MetadataController {
    private final MetadataService metadata;
    private final TransferClient transfer;
    private final VerificationCleanupService cleanup;
    public MetadataController(MetadataService metadata, TransferClient transfer, VerificationCleanupService cleanup) {this.metadata=metadata;this.transfer=transfer;this.cleanup=cleanup;}
    @PostMapping("/plans") public MetadataPlan create(@AuthenticationPrincipal Jwt jwt,@RequestBody MetadataService.CreatePlan request, @RequestHeader(value="X-MMIV-Verification-Run",required=false) UUID run) {
        return metadata.create(jwt,request,false,run);
    }
    @PostMapping("/allocations") public MetadataPlan allocate(@AuthenticationPrincipal Jwt jwt,@RequestBody MetadataService.CreatePlan request, @RequestHeader(value="X-MMIV-Verification-Run",required=false) UUID run) {
        return metadata.create(jwt,request,true,run);
    }
    @GetMapping("/verification/{run}/plans") public java.util.List<MetadataPlan> verificationPlans(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID run) {return cleanup.plans(jwt,run);}
    @PostMapping("/verification/{run}/cleanup") public Object cleanup(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID run) {return cleanup.clean(jwt,run);}
    @GetMapping("/plans/{id}") public MetadataPlan read(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        return metadata.get(jwt,id,"READ");
    }
    @GetMapping("/plans/{id}/resolve") public MetadataPlan resolve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        return metadata.get(jwt,id,"WRITE");
    }
    @PostMapping("/plans/{id}/transfer") public Object transfer(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        metadata.get(jwt,id,"WRITE"); return transfer.transfer(id,jwt.getTokenValue());
    }
}

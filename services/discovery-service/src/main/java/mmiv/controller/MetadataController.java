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
    public MetadataController(MetadataService metadata, TransferClient transfer) {this.metadata=metadata;this.transfer=transfer;}
    @PostMapping("/plans") public MetadataPlan create(@AuthenticationPrincipal Jwt jwt,@RequestBody MetadataService.CreatePlan request) {
        return metadata.create(jwt,request,false);
    }
    @PostMapping("/allocations") public MetadataPlan allocate(@AuthenticationPrincipal Jwt jwt,@RequestBody MetadataService.CreatePlan request) {
        return metadata.create(jwt,request,true);
    }
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

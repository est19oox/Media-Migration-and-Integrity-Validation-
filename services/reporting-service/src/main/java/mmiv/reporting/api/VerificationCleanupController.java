package mmiv.reporting.api;

import java.util.UUID;
import mmiv.reporting.report.VerificationCleanupGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/verification")
public class VerificationCleanupController {
    private final RestClient metadata;
    private final VerificationCleanupGuard cleanup;
    public VerificationCleanupController(@Value("${mmiv.metadata.url:http://discovery-service:8085}") String url,VerificationCleanupGuard cleanup){
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        this.metadata=RestClient.builder().baseUrl(url).requestFactory(factory).build();this.cleanup=cleanup;
    }
    @PostMapping("/{run}/cleanup") public Object cleanup(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID run){
        Plan[] plans;
        try{plans=metadata.get().uri("/api/metadata/verification/{run}/plans",run)
            .header("Authorization","Bearer "+jwt.getTokenValue()).retrieve().body(Plan[].class);}
        catch(HttpClientErrorException error){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cleanup permission required");}
        catch(RestClientException error){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Metadata unavailable");}
        if(plans==null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid cleanup assignments");
        for(var plan:plans) if(plan.metadataId()==null || !jwt.getSubject().equals(plan.ownerId()) || !run.equals(plan.verificationRunId()))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid cleanup assignment");
        for(var plan:plans)cleanup.clean(plan.metadataId());
        return java.util.Map.of("cleanedReports",plans.length);
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    public record Plan(UUID metadataId,String ownerId,UUID verificationRunId) {}
}

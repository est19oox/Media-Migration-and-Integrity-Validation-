package mmiv.integration;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class VerificationCleanupService {
    private final MetadataPlanRepository repository;
    private final PermissionClient permissions;
    private final String destination;
    private final RestClient transfer;
    private final RestClient reporting;
    public VerificationCleanupService(MetadataPlanRepository repository,PermissionClient permissions,
            @Value("${mmiv.metadata.destination-id:${mmiv.s3.bucket:media-migration-dev-landing}}") String destination,
            @Value("${mmiv.transfer.url:http://transfer-service:8081}") String transferUrl,
            @Value("${mmiv.reporting.url:http://reporting-service:8084}") String reportingUrl) {
        this.repository=repository;this.permissions=permissions;this.destination=destination;
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());
        factory.setReadTimeout(java.time.Duration.ofMinutes(5));
        transfer=RestClient.builder().baseUrl(transferUrl).requestFactory(factory).build();
        reporting=RestClient.builder().baseUrl(reportingUrl).requestFactory(factory).build();
    }
    public List<MetadataPlan> plans(Jwt jwt,UUID run) {
        permissions.require(jwt.getTokenValue(),jwt.getSubject(),destination,"WRITE");
        return repository.verificationPlans(jwt.getSubject(),run);
    }
    public Object clean(Jwt jwt,UUID run) {
        plans(jwt,run); // Authorize before closing or deleting anything.
        repository.closeRun(jwt.getSubject(),run);
        try {
            transfer.post().uri("/api/v1/verification/{run}/cleanup",run).header("Authorization","Bearer "+jwt.getTokenValue()).retrieve().toBodilessEntity();
            reporting.post().uri("/api/verification/{run}/cleanup",run).header("Authorization","Bearer "+jwt.getTokenValue()).retrieve().toBodilessEntity();
        } catch(RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Verification cleanup incomplete; retry with the same run ID");
        }
        return Map.of("deletedPlans",repository.deleteVerificationPlans(jwt.getSubject(),run),"runId",run);
    }
}

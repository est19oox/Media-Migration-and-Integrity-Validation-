package mmiv.transfer.service;

import java.util.UUID;
import mmiv.transfer.integration.*;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Service
public class VerificationCleanupService {
    private final MetadataClient metadata;
    private final VerificationRunGuard runs;
    private final S3Client s3;
    public VerificationCleanupService(MetadataClient metadata,VerificationRunGuard runs,S3Client s3){this.metadata=metadata;this.runs=runs;this.s3=s3;}
    public Object cleanup(UUID run) {
        var plans=metadata.verificationPlans(run); // Validate every assignment before the first delete.
        runs.cleanup(MetadataClient.currentJwt().getSubject(),run,()->{
            for(var plan:plans) {
                var request=ListObjectVersionsRequest.builder().bucket(plan.bucket()).prefix(plan.destinationKey()).build();
                var deletes=new java.util.ArrayList<DeleteObjectRequest>();
                for(var page:s3.listObjectVersionsPaginator(request)) {
                    for(var version:page.versions()) if(version.key().equals(plan.destinationKey()))
                        deletes.add(DeleteObjectRequest.builder().bucket(plan.bucket()).key(version.key()).versionId(version.versionId()).build());
                    for(var marker:page.deleteMarkers()) if(marker.key().equals(plan.destinationKey()))
                        deletes.add(DeleteObjectRequest.builder().bucket(plan.bucket()).key(marker.key()).versionId(marker.versionId()).build());
                }
                // Finish listing before deletion so pagination markers cannot be invalidated.
                for(var delete:deletes)s3.deleteObject(delete);
            }
        });
        return java.util.Map.of("cleanedPlans",plans.size());
    }
}

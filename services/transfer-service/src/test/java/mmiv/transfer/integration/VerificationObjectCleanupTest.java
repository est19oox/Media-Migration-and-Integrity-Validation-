package mmiv.transfer.integration;

import java.util.*;
import mmiv.transfer.service.VerificationCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectVersionsIterable;
import static org.mockito.Mockito.*;

class VerificationObjectCleanupTest {
  @Test void deletesExactAssignedVersionsAndMarkersWithoutDeletingAdjacentKeys() {
    var metadata=mock(MetadataClient.class);var s3=mock(S3Client.class);var run=UUID.randomUUID();var id=UUID.randomUUID();
    String key="migrations/"+id+"/test.txt";
    var plan=new MetadataClient.Plan(id,id,UUID.randomUUID(),"owner","bucket","bucket",key,null,"test.txt","text/plain",1,"UPLOAD","test.txt",run);
    when(metadata.verificationPlans(run)).thenReturn(List.of(plan));
    when(s3.listObjectVersionsPaginator(any(ListObjectVersionsRequest.class))).thenAnswer(call->new ListObjectVersionsIterable(s3,call.getArgument(0)));
    when(s3.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(ListObjectVersionsResponse.builder().isTruncated(false)
      .versions(ObjectVersion.builder().key(key).versionId("v1").build(),ObjectVersion.builder().key(key+"-real").versionId("real").build())
      .deleteMarkers(DeleteMarkerEntry.builder().key(key).versionId("marker").build()).build());
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg","RS256").subject("owner").build()));
    try {
      new VerificationCleanupService(metadata,new VerificationRunGuard(),s3).cleanup(run);
      verify(s3).deleteObject(DeleteObjectRequest.builder().bucket("bucket").key(key).versionId("v1").build());
      verify(s3).deleteObject(DeleteObjectRequest.builder().bucket("bucket").key(key).versionId("marker").build());
      verify(s3,times(2)).deleteObject(any(DeleteObjectRequest.class));
    } finally {SecurityContextHolder.clearContext();}
  }
}

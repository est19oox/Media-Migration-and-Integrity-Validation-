package mmiv.transfer.service;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
/** List before mutating pagination; remove versions and delete markers, including unversioned keys. */
@Service
public class StorageDeletionService {
  private final S3Client s3;
  public StorageDeletionService(S3Client s3){this.s3=s3;}
  public int delete(String bucket,String prefix,java.util.function.Predicate<String> matches){
    var objects=new ArrayList<ObjectIdentifier>();
    for(var page:s3.listObjectVersionsPaginator(ListObjectVersionsRequest.builder().bucket(bucket).prefix(prefix).build())){
      for(var version:page.versions())if(matches.test(version.key()))objects.add(ObjectIdentifier.builder().key(version.key()).versionId(version.versionId()).build());
      for(var marker:page.deleteMarkers())if(matches.test(marker.key()))objects.add(ObjectIdentifier.builder().key(marker.key()).versionId(marker.versionId()).build());
    }
    for(int i=0;i<objects.size();i+=1000){var result=s3.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket).delete(Delete.builder().objects(objects.subList(i,Math.min(objects.size(),i+1000))).quiet(true).build()).build());
      if(!result.errors().isEmpty())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Some stored versions could not be deleted. Retry deletion.");}
    return objects.size();
  }
}

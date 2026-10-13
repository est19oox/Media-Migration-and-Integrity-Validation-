package mmiv.transfer.service;
import java.util.*;
import mmiv.transfer.integration.MetadataClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
@Service
public class ExistingFileNames {
  private final S3Client s3;private final MetadataClient metadata;private final String bucket;
  public ExistingFileNames(S3Client s3,MetadataClient metadata,@Value("${mmiv.s3.bucket}") String bucket){this.s3=s3;this.metadata=metadata;this.bucket=bucket;}
  public Set<String> paths(String folder){metadata.requireFolder(folder,"WRITE");var names=new HashSet<String>();for(var page:s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(folder+"/").build()))for(var object:page.contents())if(!object.key().endsWith("/"))names.add(FileMutationService.relative(object.key(),folder));return names;}
}

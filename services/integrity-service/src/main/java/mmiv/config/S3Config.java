package mmiv.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3Config {

  @Bean
  public S3Client s3Client(
      @org.springframework.beans.factory.annotation.Value("${aws.region:ap-southeast-1}")
          String region,
      @org.springframework.beans.factory.annotation.Value("${mmiv.s3.endpoint:}") String endpoint) {
    var builder = S3Client.builder().region(software.amazon.awssdk.regions.Region.of(region));
    if (!endpoint.isBlank())
      builder.endpointOverride(java.net.URI.create(endpoint)).forcePathStyle(true);
    return builder.build();
  }
}

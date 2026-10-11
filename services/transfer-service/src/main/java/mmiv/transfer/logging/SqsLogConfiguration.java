package mmiv.transfer.logging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import java.time.Duration;

@Configuration
@EnableScheduling
public class SqsLogConfiguration {
    @Bean(destroyMethod = "close")
    public SqsClient sqsLogClient(@Value("${aws.region}") String region) {
        return SqsClient.builder().region(Region.of(region))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3)))
                .build();
    }
}

package mmiv.transfer.integration;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableFeignClients(clients = IntegrityClient.class)
public class IntegrityFeignConfiguration {}

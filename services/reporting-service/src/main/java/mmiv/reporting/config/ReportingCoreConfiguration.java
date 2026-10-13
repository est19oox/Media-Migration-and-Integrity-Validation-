package mmiv.reporting.config;

import java.time.Clock;
import mmiv.reporting.contract.EventContractValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class ReportingCoreConfiguration {

  @Bean
  EventContractValidator eventContractValidator(ObjectMapper objectMapper) {
    return new EventContractValidator(objectMapper);
  }

  @Bean
  Clock reportingClock() {
    return Clock.systemUTC();
  }
}

package mmiv.transfer.config;

import mmiv.transfer.factory.S3TransferStrategyFactory;
import mmiv.transfer.factory.TransferStrategyFactory;
import mmiv.transfer.strategy.S3TransferStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit composition root for the selected transfer backend. */
@Configuration
public class TransferFactoryConfiguration {
  @Bean
  TransferStrategyFactory transferStrategyFactory(S3TransferStrategy strategy) {
    return new S3TransferStrategyFactory(strategy);
    // Alternate backend: return new AzureTransferStrategyFactory(azureStrategy);
  }
}

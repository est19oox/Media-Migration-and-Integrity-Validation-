package mmiv.transfer.factory;

import java.util.Objects;
import mmiv.transfer.strategy.S3TransferStrategy;
import mmiv.transfer.strategy.TransferBackend;
import mmiv.transfer.strategy.TransferStrategy;
// import mmiv.transfer.strategy.AzureTransferStrategy; // Alternate, not implemented/enabled.
import org.springframework.stereotype.Component;

/** TransferService supplies the backend; Spring only creates the explicitly named dependencies. */
@Component
public class TransferStrategyFactory {
  private final S3TransferStrategy s3Strategy;

  // private final AzureTransferStrategy azureStrategy;

  public TransferStrategyFactory(S3TransferStrategy s3Strategy) {
    this.s3Strategy = Objects.requireNonNull(s3Strategy, "S3 transfer strategy is required");
    // To enable Azure, implement AzureTransferStrategy, inject it explicitly here,
    // and add matching metadata, integrity, and cleanup support first.
    // this.azureStrategy = azureStrategy;
  }

  public TransferStrategy getStrategy(TransferBackend backend) {
    if (backend == null) throw new IllegalArgumentException("Transfer backend is required");
    return switch (backend) {
      case S3 -> s3Strategy;
        // case AZURE -> azureStrategy; // Alternate strategy: enable deliberately, never race
        // uploads.
    };
  }
}

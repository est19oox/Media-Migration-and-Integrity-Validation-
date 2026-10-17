package mmiv.transfer.factory;

import java.util.Objects;
import mmiv.transfer.strategy.S3TransferStrategy;
import mmiv.transfer.strategy.TransferBackend;
import mmiv.transfer.strategy.TransferStrategy;

/** Concrete Creator that supplies the S3 transfer product. */
public final class S3TransferStrategyFactory extends TransferStrategyFactory {
  private final S3TransferStrategy strategy;

  public S3TransferStrategyFactory(S3TransferStrategy strategy) {
    this.strategy = Objects.requireNonNull(strategy, "S3 transfer strategy is required");
  }

  @Override
  public TransferBackend backend() {
    return TransferBackend.S3;
  }

  @Override
  protected TransferStrategy createStrategy() {
    return strategy;
  }
}

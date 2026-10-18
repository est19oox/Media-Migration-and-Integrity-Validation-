package mmiv.transfer.factory;

import java.util.Objects;
import mmiv.transfer.strategy.TransferBackend;
import mmiv.transfer.strategy.TransferStrategy;

/** GoF Factory Method creator for a configured storage transport. */
public abstract class TransferStrategyFactory {
  public TransferStrategy getStrategy(TransferBackend requestedBackend) {
    if (requestedBackend == null)
      throw new IllegalArgumentException("Transfer backend is required");
    if (requestedBackend != backend())
      throw new IllegalArgumentException("Unsupported transfer backend: " + requestedBackend);
    TransferStrategy strategy =
        Objects.requireNonNull(createStrategy(), "Transfer strategy is required");
    if (strategy.backend() != requestedBackend)
      throw new IllegalStateException("Factory created a strategy for the wrong backend");
    return strategy;
  }

  public abstract TransferBackend backend();

  /** Factory Method implemented by each concrete storage creator. */
  protected abstract TransferStrategy createStrategy();
}

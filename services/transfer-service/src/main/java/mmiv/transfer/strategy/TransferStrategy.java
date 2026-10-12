package mmiv.transfer.strategy;

import mmiv.transfer.dto.PreparedTransfer;
import mmiv.transfer.dto.StoredTransfer;

/** A storage transport consumes the same prepared bytes for every supported source/media type. */
public interface TransferStrategy {
    TransferBackend backend();
    StoredTransfer transfer(PreparedTransfer source);
}

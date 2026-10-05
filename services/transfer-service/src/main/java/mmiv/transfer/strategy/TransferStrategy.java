package mmiv.transfer.strategy;

import mmiv.transfer.dto.TransferRequest;
import mmiv.transfer.dto.TransferResponse;

public interface TransferStrategy {
    boolean supports(TransferRequest request);
    TransferResponse transfer(TransferRequest request);
}

package mmiv.transfer.strategy;

import mmiv.transfer.dto.TransferRequest;
import mmiv.transfer.dto.TransferResponse;
import mmiv.transfer.dto.UploadSource;
import mmiv.transfer.dto.UploadResponse;

public interface TransferStrategy {
    boolean supports(TransferRequest request);
    TransferResponse transfer(TransferRequest request);
    UploadResponse transferUploaded(UploadSource source);
}

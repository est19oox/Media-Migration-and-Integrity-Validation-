package mmiv.transfer.service;

import mmiv.transfer.dto.TransferRequest;
import mmiv.transfer.dto.TransferResponse;
import mmiv.transfer.factory.TransferStrategyFactory;
import mmiv.transfer.strategy.TransferStrategy;
import mmiv.transfer.validation.TransferRequestValidator;
import org.springframework.stereotype.Service;

@Service
public class TransferService {
    private final TransferRequestValidator validator;
    private final TransferStrategyFactory factory;

    public TransferService(TransferRequestValidator validator, TransferStrategyFactory factory) {
        this.validator = validator;
        this.factory = factory;
    }

    public TransferResponse transfer(TransferRequest request) {
        validator.validate(request);
        TransferStrategy strategy = factory.getStrategy(request);
        return strategy.transfer(request);
    }
}

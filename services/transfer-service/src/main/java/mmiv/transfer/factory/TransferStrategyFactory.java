package mmiv.transfer.factory;

import mmiv.transfer.dto.TransferRequest;
import mmiv.transfer.strategy.TransferStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TransferStrategyFactory {
    private final List<TransferStrategy> strategies;

    public TransferStrategyFactory(List<TransferStrategy> strategies) {
        this.strategies = strategies;
    }

    public TransferStrategy getStrategy(TransferRequest request) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(request))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported media type: " + request.sourcePath()));
    }
}

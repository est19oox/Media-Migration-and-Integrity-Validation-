package mmiv.transfer.validation;

import mmiv.transfer.dto.TransferRequest;
import org.springframework.stereotype.Component;

@Component
public class TransferRequestValidator {
    public void validate(TransferRequest request) {
        if (request == null) throw new IllegalArgumentException("Request is required");
        if (request.sourcePath() == null || request.sourcePath().isBlank()) throw new IllegalArgumentException("sourcePath is required");
        if (request.destinationKey() == null || request.destinationKey().isBlank()) throw new IllegalArgumentException("destinationKey is required");
        if (request.destinationKey().startsWith("/")) throw new IllegalArgumentException("destinationKey must be relative");
    }
}

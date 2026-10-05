package mmiv.transfer.service;

import mmiv.transfer.dto.*;
import mmiv.transfer.factory.TransferStrategyFactory;
import mmiv.transfer.strategy.TransferStrategy;
import mmiv.transfer.validation.TransferRequestValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferServiceTest {
    TransferRequestValidator validator;
    TransferStrategyFactory factory;
    TransferStrategy strategy;
    TransferService service;
    final TransferRequest request = new TransferRequest("sample.txt", "key", "text/plain");
    @BeforeEach void setUp() {
        validator = mock(TransferRequestValidator.class);
        factory = mock(TransferStrategyFactory.class);
        strategy = mock(TransferStrategy.class);
        service = new TransferService(validator, factory);
    }
    @Test void validatesThenSelectsThenTransfers() {
        var response = new TransferResponse("bucket", "key", "text/plain", 5);
        when(factory.getStrategy(request)).thenReturn(strategy);
        when(strategy.transfer(request)).thenReturn(response);
        assertSame(response, service.transfer(request));
        var order = inOrder(validator, factory, strategy);
        order.verify(validator).validate(request);
        order.verify(factory).getStrategy(request);
        order.verify(strategy).transfer(request);
    }
    @Test void invalidRequestStopsBeforeStrategySelection() {
        var error = new IllegalArgumentException("invalid request");
        doThrow(error).when(validator).validate(request);
        assertSame(error, assertThrows(IllegalArgumentException.class, () -> service.transfer(request)));
        verifyNoInteractions(factory, strategy);
    }
    @Test void unsupportedMediaStopsBeforeTransfer() {
        var error = new IllegalArgumentException("unsupported");
        when(factory.getStrategy(request)).thenThrow(error);
        assertSame(error, assertThrows(IllegalArgumentException.class, () -> service.transfer(request)));
        verifyNoInteractions(strategy);
    }
    @Test void propagatesStorageFailure() {
        var error = new IllegalStateException("storage unavailable");
        when(factory.getStrategy(request)).thenReturn(strategy);
        when(strategy.transfer(request)).thenThrow(error);
        assertSame(error, assertThrows(IllegalStateException.class, () -> service.transfer(request)));
    }
}

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
    S3ObjectCleanup cleanup;
    mmiv.transfer.logging.FileEventLogger logs;
    mmiv.transfer.integration.IntegrityValidationGateway integrity;
    final TransferRequest request = new TransferRequest("sample.txt", "key", "text/plain");
    @BeforeEach void setUp() {
        validator = mock(TransferRequestValidator.class);
        factory = mock(TransferStrategyFactory.class);
        strategy = mock(TransferStrategy.class);
        integrity = mock(mmiv.transfer.integration.IntegrityValidationGateway.class);
        when(integrity.validate(anyString(), anyString(), anyString(), nullable(String.class))).thenReturn(
                new mmiv.transfer.integration.IntegrityValidationGateway.Outcome("PASSED", true, "Verified"));
        cleanup = mock(S3ObjectCleanup.class);
        when(cleanup.delete(anyString(), anyString(), nullable(S3ObjectReference.class)))
                .thenReturn(new S3ObjectCleanup.Result("DELETED", "Uploaded file deleted"));
        logs = mock(mmiv.transfer.logging.FileEventLogger.class);
        service = new TransferService(validator, factory, integrity, cleanup, logs);
    }
    @Test void validatesThenSelectsThenTransfers() {
        var response = new TransferResponse("bucket", "key", "text/plain", 5);
        when(factory.getStrategy(request)).thenReturn(strategy);
        when(strategy.transfer(request)).thenReturn(response);
        var result = service.transfer(request);
        assertEquals(response.key(), result.key());
        assertTrue(result.success());
        assertEquals("PASSED", result.validationResult());
        assertNotNull(result.transferId());
        verify(logs).transfer(eq(result), any(java.time.Instant.class));
        var order = inOrder(validator, factory, strategy, integrity);
        order.verify(validator).validate(request);
        order.verify(factory).getStrategy(request);
        order.verify(strategy).transfer(request);
        order.verify(integrity).validate(eq(result.transferId()), eq("bucket"), eq("key"), isNull());
    }
    @Test void invalidRequestStopsBeforeStrategySelection() {
        var error = new IllegalArgumentException("invalid request");
        doThrow(error).when(validator).validate(request);
        assertSame(error, assertThrows(IllegalArgumentException.class, () -> service.transfer(request)));
        verifyNoInteractions(factory, strategy);
        verify(logs).failure(eq("TRANSFER"), isNull(), eq("key"), eq(error), any(java.time.Instant.class));
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

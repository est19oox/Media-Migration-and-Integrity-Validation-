package mmiv.transfer.service;

import mmiv.transfer.dto.*;
import mmiv.transfer.factory.TransferStrategyFactory;
import mmiv.transfer.strategy.TransferBackend;
import mmiv.transfer.strategy.TransferStrategy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferServiceTest {
    @TempDir Path directory;
    TransferSourcePreparer preparer;
    TransferStrategyFactory factory;
    TransferStrategy strategy;
    TransferService service;
    PreparedTransfer source;
    S3ObjectCleanup cleanup;
    mmiv.transfer.logging.FileEventLogger logs;
    mmiv.transfer.integration.IntegrityValidationGateway integrity;
    final TransferRequest request = new TransferRequest("sample.txt", "key", "text/plain");
    @BeforeEach void setUp() throws Exception {
        preparer = mock(TransferSourcePreparer.class);
        factory = mock(TransferStrategyFactory.class);
        strategy = mock(TransferStrategy.class);
        source = new PreparedTransfer(Files.writeString(directory.resolve("snapshot"), "hello"),
                "sample.txt", "key", "text/plain", 5, "a".repeat(64));
        when(preparer.prepare(request)).thenReturn(source);
        integrity = mock(mmiv.transfer.integration.IntegrityValidationGateway.class);
        when(integrity.validate(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new mmiv.transfer.integration.IntegrityValidationGateway.Outcome("PASSED", true, "Verified"));
        cleanup = mock(S3ObjectCleanup.class);
        logs = mock(mmiv.transfer.logging.FileEventLogger.class);
        service = new TransferService(preparer, factory, integrity, cleanup, logs);
    }
    @Test void preparesThenStoresThenValidatesAndClosesSnapshot() {
        when(factory.getStrategy(TransferBackend.S3)).thenReturn(strategy);
        when(strategy.transfer(source)).thenReturn(new StoredTransfer("bucket", "key", null));
        var result = service.transfer(request);
        assertTrue(result.success());
        assertEquals(source.sha256(), result.sha256());
        assertEquals("PASSED", result.validationResult());
        assertFalse(Files.exists(source.path()));
        verify(logs).transfer(eq(result), any(java.time.Instant.class));
        var order = inOrder(preparer, factory, strategy, integrity);
        order.verify(preparer).prepare(request);
        order.verify(factory).getStrategy(TransferBackend.S3);
        order.verify(strategy).transfer(source);
        order.verify(integrity).validate(result.transferId(), "bucket", "key", source.sha256());
        verifyNoInteractions(cleanup);
    }
    @Test void preparationFailureStopsBeforeStrategySelection() {
        var error = new IllegalArgumentException("invalid request");
        when(preparer.prepare(request)).thenThrow(error);
        assertSame(error, assertThrows(IllegalArgumentException.class, () -> service.transfer(request)));
        verifyNoInteractions(factory, strategy, integrity, cleanup);
        verify(logs).failure(eq("TRANSFER"), isNull(), eq("key"), eq(error), any(java.time.Instant.class));
    }
    @Test void selectionFailureClosesSnapshotWithoutStorage() {
        var error = new IllegalArgumentException("unsupported");
        when(factory.getStrategy(TransferBackend.S3)).thenThrow(error);
        assertSame(error, assertThrows(IllegalArgumentException.class, () -> service.transfer(request)));
        assertFalse(Files.exists(source.path()));
        verifyNoInteractions(strategy, integrity, cleanup);
    }
    @Test void storageFailureClosesSnapshotWithoutIntegrityOrRemoteCleanup() {
        var error = new IllegalStateException("storage unavailable");
        when(factory.getStrategy(TransferBackend.S3)).thenReturn(strategy);
        when(strategy.transfer(source)).thenThrow(error);
        assertSame(error, assertThrows(IllegalStateException.class, () -> service.transfer(request)));
        assertFalse(Files.exists(source.path()));
        verifyNoInteractions(integrity, cleanup);
    }
}

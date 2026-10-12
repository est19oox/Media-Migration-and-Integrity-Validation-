package mmiv.transfer.factory;

import mmiv.transfer.strategy.S3TransferStrategy;
import mmiv.transfer.strategy.TransferBackend;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferStrategyFactoryTest {
    @Test void explicitlySelectsS3WithoutProbingOrExecutingTheStrategy() {
        var s3 = mock(S3TransferStrategy.class);
        assertSame(s3, new TransferStrategyFactory(s3).getStrategy(TransferBackend.S3));
        verifyNoInteractions(s3);
    }
    @Test void selectionAlwaysReturnsTheInjectedInstance() {
        var s3 = mock(S3TransferStrategy.class);
        var factory = new TransferStrategyFactory(s3);
        assertSame(s3, factory.getStrategy(TransferBackend.S3));
        assertSame(s3, factory.getStrategy(TransferBackend.S3));
        verifyNoInteractions(s3);
    }
    @Test void rejectsMissingBackendWithoutCallingStorage() {
        var s3 = mock(S3TransferStrategy.class);
        assertThrows(IllegalArgumentException.class, () -> new TransferStrategyFactory(s3).getStrategy(null));
        verifyNoInteractions(s3);
    }
    @Test void rejectsMissingS3DependencyAtConstruction() {
        assertThrows(NullPointerException.class, () -> new TransferStrategyFactory(null));
    }
}

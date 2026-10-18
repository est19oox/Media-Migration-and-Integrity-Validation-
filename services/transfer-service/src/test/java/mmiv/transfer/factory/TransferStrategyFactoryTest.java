package mmiv.transfer.factory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import mmiv.transfer.strategy.S3TransferStrategy;
import mmiv.transfer.strategy.TransferBackend;
import org.junit.jupiter.api.Test;

class TransferStrategyFactoryTest {
  @Test
  void explicitlySelectsS3WithoutProbingOrExecutingTheStrategy() {
    var s3 = mock(S3TransferStrategy.class);
    when(s3.backend()).thenReturn(TransferBackend.S3);
    assertSame(s3, new S3TransferStrategyFactory(s3).getStrategy(TransferBackend.S3));
    verify(s3).backend();
    verifyNoMoreInteractions(s3);
  }

  @Test
  void selectionAlwaysReturnsTheInjectedInstance() {
    var s3 = mock(S3TransferStrategy.class);
    when(s3.backend()).thenReturn(TransferBackend.S3);
    var factory = new S3TransferStrategyFactory(s3);
    assertSame(s3, factory.getStrategy(TransferBackend.S3));
    assertSame(s3, factory.getStrategy(TransferBackend.S3));
    verify(s3, times(2)).backend();
    verifyNoMoreInteractions(s3);
  }

  @Test
  void rejectsMissingBackendWithoutCallingStorage() {
    var s3 = mock(S3TransferStrategy.class);
    assertThrows(
        IllegalArgumentException.class, () -> new S3TransferStrategyFactory(s3).getStrategy(null));
    verifyNoInteractions(s3);
  }

  @Test
  void rejectsMissingS3DependencyAtConstruction() {
    assertThrows(NullPointerException.class, () -> new S3TransferStrategyFactory(null));
  }
}

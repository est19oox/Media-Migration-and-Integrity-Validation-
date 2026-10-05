package mmiv.transfer.factory;

import mmiv.transfer.dto.TransferRequest;
import mmiv.transfer.strategy.TransferStrategy;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferStrategyFactoryTest {
    private final TransferRequest request = new TransferRequest("image.png", "image.png", null);
    @Test void selectsMatchingStrategy() {
        var rejected = mock(TransferStrategy.class);
        var selected = mock(TransferStrategy.class);
        when(selected.supports(request)).thenReturn(true);
        assertSame(selected, new TransferStrategyFactory(List.of(rejected, selected)).getStrategy(request));
    }
    @Test void selectsFirstMatchAndStopsSearching() {
        var first = mock(TransferStrategy.class);
        var second = mock(TransferStrategy.class);
        when(first.supports(request)).thenReturn(true);
        assertSame(first, new TransferStrategyFactory(List.of(first, second)).getStrategy(request));
        verifyNoInteractions(second);
    }
    @Test void rejectsUnsupportedMedia() {
        var strategy = mock(TransferStrategy.class);
        assertThrows(IllegalArgumentException.class, () -> new TransferStrategyFactory(List.of(strategy)).getStrategy(request));
        verify(strategy, never()).transfer(any());
    }
    @Test void rejectsEmptyStrategyList() {
        assertThrows(IllegalArgumentException.class, () -> new TransferStrategyFactory(List.of()).getStrategy(request));
    }
}

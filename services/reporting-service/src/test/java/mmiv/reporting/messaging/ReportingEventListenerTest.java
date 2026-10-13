package mmiv.reporting.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import java.time.Duration;
import mmiv.reporting.config.ReportingProperties;
import mmiv.reporting.contract.EventContractException;
import mmiv.reporting.processing.EventIntegrityConflictException;
import mmiv.reporting.processing.EventProcessingResult;
import mmiv.reporting.processing.EventProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;

class ReportingEventListenerTest {

  private static final long DELIVERY_TAG = 42L;

  private EventProcessor processor;

  private Channel channel;

  private ReportingEventListener listener;

  @BeforeEach
  void setUp() {
    processor = mock(EventProcessor.class);
    channel = mock(Channel.class);

    ReportingProperties properties = new ReportingProperties();
    properties.setRetryAttempts(3);
    properties.setRetryInitialDelay(Duration.ZERO);

    listener = new ReportingEventListener(processor, properties);
  }

  @Test
  void appliedEventIsAcknowledgedOnce() throws Exception {
    when(processor.process(any(byte[].class))).thenReturn(EventProcessingResult.APPLIED);

    listener.receive(message(), channel);

    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(channel, never()).basicReject(DELIVERY_TAG, false);
  }

  @Test
  void appliedDuplicateIsAcknowledgedOnce() throws Exception {
    when(processor.process(any(byte[].class))).thenReturn(EventProcessingResult.DUPLICATE_APPLIED);

    listener.receive(message(), channel);

    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(processor).process(any(byte[].class));
  }

  @Test
  void invalidContractIsRejectedWithoutRetry() throws Exception {
    doThrow(new EventContractException("invalid")).when(processor).process(any(byte[].class));

    listener.receive(message(), channel);

    verify(processor).process(any(byte[].class));
    verify(channel).basicReject(DELIVERY_TAG, false);
    verify(channel, never()).basicAck(DELIVERY_TAG, false);
  }

  @Test
  void integrityConflictIsRejectedWithoutRetry() throws Exception {
    doThrow(new EventIntegrityConflictException("conflict"))
        .when(processor)
        .process(any(byte[].class));

    listener.receive(message(), channel);

    verify(processor).process(any(byte[].class));
    verify(channel).basicReject(DELIVERY_TAG, false);
  }

  @Test
  void exhaustedTransientFailureRetriesThreeTimesThenRejects() throws Exception {
    doThrow(new DataAccessResourceFailureException("mongo unavailable"))
        .when(processor)
        .process(any(byte[].class));

    listener.receive(message(), channel);

    verify(processor, times(3)).process(any(byte[].class));
    verify(channel).basicReject(DELIVERY_TAG, false);
    verify(channel, never()).basicAck(DELIVERY_TAG, false);
  }

  @Test
  void transientFailureThenSuccessIsAcknowledged() throws Exception {
    when(processor.process(any(byte[].class)))
        .thenThrow(new DataAccessResourceFailureException("temporary"))
        .thenReturn(EventProcessingResult.APPLIED);

    assertDoesNotThrow(() -> listener.receive(message(), channel));

    verify(processor, times(2)).process(any(byte[].class));
    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(channel, never()).basicReject(DELIVERY_TAG, false);
  }

  private Message message() {
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(DELIVERY_TAG);

    return new Message("{}".getBytes(), properties);
  }
}

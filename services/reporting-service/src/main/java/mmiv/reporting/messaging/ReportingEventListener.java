package mmiv.reporting.messaging;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import mmiv.reporting.config.ReportingProperties;
import mmiv.reporting.contract.EventContractException;
import mmiv.reporting.processing.EventIntegrityConflictException;
import mmiv.reporting.processing.EventProcessor;

@Component
public class ReportingEventListener {

    private final EventProcessor processor;

    private final RetryTemplate retryTemplate;

    public ReportingEventListener(
            EventProcessor processor,
            ReportingProperties properties
    ) {
        this.processor = Objects.requireNonNull(processor);
        Objects.requireNonNull(properties);

        SimpleRetryPolicy retryPolicy =
                new SimpleRetryPolicy(
                        properties.getRetryAttempts(),
                        Map.of(DataAccessException.class, true),
                        true
                );

        FixedBackOffPolicy backOffPolicy =
                new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(
                properties.getRetryInitialDelay().toMillis()
        );

        retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);
    }

    @RabbitListener(
            queues = "${mmiv.reporting.queue-name:reporting.events}",
            ackMode = "MANUAL"
    )
    public void receive(
            Message message,
            Channel channel
    ) throws IOException {
        long deliveryTag =
                message.getMessageProperties()
                        .getDeliveryTag();

        try {
            retryTemplate.execute(context ->
                    processor.process(message.getBody())
            );
            channel.basicAck(deliveryTag, false);
        } catch (EventContractException
                 | EventIntegrityConflictException exception) {
            channel.basicReject(deliveryTag, false);
        } catch (RuntimeException exception) {
            channel.basicReject(deliveryTag, false);
        }
    }
}

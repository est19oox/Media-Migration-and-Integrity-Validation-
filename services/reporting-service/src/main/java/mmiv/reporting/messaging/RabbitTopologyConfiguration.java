package mmiv.reporting.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import mmiv.reporting.config.ReportingProperties;
import mmiv.reporting.contract.EventType;

@Configuration
public class RabbitTopologyConfiguration {

    private static final String DEAD_LETTER_ROUTING_KEY =
            "reporting.events.dead";

    private final ReportingProperties properties;

    public RabbitTopologyConfiguration(
            ReportingProperties properties
    ) {
        this.properties = Objects.requireNonNull(properties);
    }

    @Bean
    public Declarables reportingTopology() {
        TopicExchange exchange = ExchangeBuilder
                .topicExchange(properties.getExchangeName())
                .durable(true)
                .build();

        DirectExchange deadLetterExchange = ExchangeBuilder
                .directExchange(
                        properties.getDeadLetterExchange()
                )
                .durable(true)
                .build();

        Queue queue = QueueBuilder
                .durable(properties.getQueueName())
                .withArgument(
                        "x-dead-letter-exchange",
                        properties.getDeadLetterExchange()
                )
                .withArgument(
                        "x-dead-letter-routing-key",
                        DEAD_LETTER_ROUTING_KEY
                )
                .withArgument(
                        "x-single-active-consumer",
                        true
                )
                .build();

        Queue deadLetterQueue = QueueBuilder
                .durable(properties.getDeadLetterQueue())
                .build();

        List<Declarable> declarations = new ArrayList<>();
        declarations.add(exchange);
        declarations.add(deadLetterExchange);
        declarations.add(queue);
        declarations.add(deadLetterQueue);

        for (EventType eventType : EventType.values()) {
            declarations.add(
                    BindingBuilder.bind(queue)
                            .to(exchange)
                            .with(eventType.routingKey())
            );
        }

        declarations.add(
                BindingBuilder.bind(deadLetterQueue)
                        .to(deadLetterExchange)
                        .with(DEAD_LETTER_ROUTING_KEY)
        );

        return new Declarables(declarations);
    }
}

package mmiv.reporting.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AbstractExchange;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;

import mmiv.reporting.config.ReportingProperties;
import mmiv.reporting.contract.EventType;

class RabbitTopologyConfigurationTest {

    @Test
    void declaresDurableReportingTopologyAndAllBindings() {
        ReportingProperties properties =
                new ReportingProperties();
        Declarables topology =
                new RabbitTopologyConfiguration(properties)
                        .reportingTopology();

        Collection<Declarable> declarations =
                topology.getDeclarables();

        AbstractExchange exchange = declarations.stream()
                .filter(AbstractExchange.class::isInstance)
                .map(AbstractExchange.class::cast)
                .filter(value -> value.getName().equals(
                        properties.getExchangeName()
                ))
                .findFirst()
                .orElseThrow();

        AbstractExchange deadLetterExchange =
                declarations.stream()
                        .filter(AbstractExchange.class::isInstance)
                        .map(AbstractExchange.class::cast)
                        .filter(value -> value.getName().equals(
                                properties.getDeadLetterExchange()
                        ))
                        .findFirst()
                        .orElseThrow();

        Queue queue = queueNamed(
                declarations,
                properties.getQueueName()
        );
        Queue deadLetterQueue = queueNamed(
                declarations,
                properties.getDeadLetterQueue()
        );

        assertTrue(exchange.isDurable());
        assertFalse(exchange.isAutoDelete());
        assertTrue(deadLetterExchange.isDurable());
        assertTrue(queue.isDurable());
        assertTrue(deadLetterQueue.isDurable());
        assertEquals(
                properties.getDeadLetterExchange(),
                queue.getArguments().get(
                        "x-dead-letter-exchange"
                )
        );
        assertEquals(
                true,
                queue.getArguments().get(
                        "x-single-active-consumer"
                )
        );

        List<Binding> eventBindings = declarations.stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .filter(binding -> binding.getExchange().equals(
                        properties.getExchangeName()
                ))
                .toList();

        assertEquals(EventType.values().length,
                eventBindings.size());

        for (EventType eventType : EventType.values()) {
            assertTrue(eventBindings.stream().anyMatch(binding ->
                    binding.getRoutingKey().equals(
                            eventType.routingKey()
                    )
                            && binding.getDestination().equals(
                            properties.getQueueName()
                    )
            ));
        }
    }

    private Queue queueNamed(
            Collection<Declarable> declarations,
            String name
    ) {
        return declarations.stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .filter(queue -> queue.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}

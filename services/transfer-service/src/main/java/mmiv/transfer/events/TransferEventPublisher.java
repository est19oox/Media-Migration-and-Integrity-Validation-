package mmiv.transfer.events;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Publishes schema-v2 MMIV domain events to the existing Reporting exchange. */
@Component
public class TransferEventPublisher {
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String exchange;

    public TransferEventPublisher(RabbitTemplate rabbit, ObjectMapper json,
            @Value("${mmiv-events.enabled:false}") boolean enabled,
            @Value("${mmiv-events.exchange:mmiv.events}") String exchange) {
        this.rabbit = rabbit;
        this.json = json;
        this.enabled = enabled;
        this.exchange = exchange;
    }

    public static UUID fileId(UUID transferId) {
        return UUID.nameUUIDFromBytes(("mmiv:file:" + transferId).getBytes(StandardCharsets.UTF_8));
    }

    public void publish(String type, String routingKey, String source, UUID migrationId,
            UUID fileId, Map<String, Object> data) {
        if (!enabled) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", type);
        event.put("schemaVersion", 2);
        event.put("sourceService", source);
        event.put("migrationId", migrationId.toString());
        if (fileId != null) event.put("fileId", fileId.toString());
        event.put("occurredAt", Instant.now().toString());
        event.put("data", data);
        byte[] body = json.writeValueAsBytes(event);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        rabbit.send(exchange, routingKey, new Message(body, props));
    }
}

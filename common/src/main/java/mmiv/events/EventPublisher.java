package mmiv.events;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

/** Durable local outbox; a file is removed only after a broker ACK without a return. */
public class EventPublisher {
    private final RabbitTemplate rabbit;
    private final Path directory;
    private final String service;
    private final boolean enabled;
    private final JsonMapper json = JsonMapper.builder().build();
    public EventPublisher(RabbitTemplate rabbit, String directory, String service, boolean enabled) {
        this.rabbit = rabbit; this.directory = Path.of(directory); this.service = service; this.enabled = enabled;
        if (enabled) try { Files.createDirectories(this.directory); Files.setPosixFilePermissions(this.directory,
                PosixFilePermissions.fromString("rwx------")); }
        catch (IOException e) { throw new IllegalStateException("Cannot initialize event outbox", e); }
    }
    public void audit(String operation, Map<String, ?> data) {
        enqueue("service.log", Map.of("eventId", UUID.randomUUID().toString(), "sourceService", service,
                "occurredAt", Instant.now().toString(), "operation", operation, "data", data));
    }
    public void migration(String type, String routingKey, UUID migrationId, UUID fileId, Map<String, ?> data) {
        var event = new LinkedHashMap<String,Object>();
        event.put("eventId", UUID.randomUUID().toString()); event.put("eventType", type);
        event.put("schemaVersion", 2); event.put("sourceService", service);
        event.put("migrationId", migrationId); if (fileId != null) event.put("fileId", fileId);
        event.put("occurredAt", Instant.now().toString()); event.put("data", data); enqueue(routingKey, event);
    }
    public void enqueue(String route, Map<String, ?> event) {
        if (!enabled) return;
        Path temporary = null;
        try {
            byte[] bytes = json.writeValueAsBytes(Map.of("route", route, "event", event));
            temporary = Files.createTempFile(directory, ".pending-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (var out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer); out.force(true);
            }
            Files.move(temporary, directory.resolve(event.get("eventId") + ".json"), StandardCopyOption.ATOMIC_MOVE);
            try (var parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
        } catch (IOException e) { throw new IllegalStateException("Cannot persist event", e); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }
    @Scheduled(fixedDelayString = "${mmiv.events.flush-delay-ms:5000}", initialDelay = 1000)
    public synchronized void flush() {
        if (!enabled) return;
        try (var files = Files.list(directory)) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).sorted().limit(50).toList()) {
                var pending = json.readTree(Files.readAllBytes(file));
                byte[] body = json.writeValueAsBytes(pending.path("event"));
                var props = new MessageProperties(); props.setContentType("application/json");
                props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                props.setMessageId(pending.path("event").path("eventId").stringValue());
                var correlation = new CorrelationData(props.getMessageId());
                rabbit.send("mmiv.events", pending.path("route").stringValue(), new Message(body, props), correlation);
                var confirmation = correlation.getFuture().get(10, TimeUnit.SECONDS);
                if (!confirmation.ack() || correlation.getReturned() != null) return;
                Files.delete(file);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { /* Keep original eventId and bytes for the next attempt, including after restart. */ }
    }
}

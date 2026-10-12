package mmiv.transfer.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;

/** One local outbox per Transfer container; network I/O happens only on the worker. */
@Component
public class SqsLogOutbox {
    private static final Logger LOG = LoggerFactory.getLogger(SqsLogOutbox.class);
    private final SqsClient sqs;
    private final String queueUrl;
    private final Path directory;
    private final JsonMapper json = JsonMapper.builder().build();

    public SqsLogOutbox(SqsClient sqs, @Value("${mmiv.log.queue-url:}") String queueUrl,
            @Value("${mmiv.log.outbox-directory:/var/lib/mmiv/log-outbox}") String directory) throws IOException {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.directory = Path.of(directory);
        if (!queueUrl.isBlank()) {
            Files.createDirectories(this.directory);
            Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
        } else {
            LOG.warn("SQS file logging disabled: MMIV_LOG_QUEUE_URL is not configured");
        }
    }

    public void enqueue(FileLogEvent event) {
        if (queueUrl.isBlank()) return;
        Path pending = null;
        try {
            byte[] body = json.writeValueAsBytes(event);
            pending = Files.createTempFile(directory, ".pending-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (FileChannel output = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(body);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(pending, directory.resolve(event.eventId() + ".json"), StandardCopyOption.ATOMIC_MOVE);
            // Persist the rename as well as the file bytes on the Linux deployment host.
            try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
                parent.force(true);
            }
        } catch (IOException | RuntimeException error) {
            // Logging must not turn a completed upload/download into a retryable transfer failure.
            LOG.error("File log could not be persisted: eventId={} errorType={}",
                    event.eventId(), error.getClass().getSimpleName());
        } finally {
            if (pending != null) {
                try { Files.deleteIfExists(pending); }
                catch (IOException error) { LOG.warn("Could not remove incomplete file log"); }
            }
        }
    }

    @Scheduled(fixedDelayString = "${mmiv.log.flush-delay-ms:5000}", initialDelay = 1000)
    public synchronized void flush() {
        if (queueUrl.isBlank()) return;
        try (var entries = Files.list(directory)) {
            var messages = entries.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).limit(20).toList();
            for (Path message : messages) {
                // Preserve the original JSON/eventId on every retry, including after restart.
                sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl)
                        .messageBody(Files.readString(message)).build());
                Files.delete(message); // Only after SQS acknowledges receipt.
            }
        } catch (IOException | RuntimeException error) {
            LOG.warn("SQS file logs remain pending; retry scheduled: errorType={}",
                    error.getClass().getSimpleName());
        }
    }
}

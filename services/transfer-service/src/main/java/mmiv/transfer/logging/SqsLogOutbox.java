
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
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

import java.util.Comparator;

/**
 * Durable local outbox for SQS file logging.
 *
 * <p>Each Transfer container maintains its own outbox.
 * Events are persisted locally before being sent to SQS.
 *
 * <p>Network I/O occurs only in the scheduled worker.
 * Failed deliveries remain in the outbox for retry.
 *
 * <p>Supports Windows development and Linux deployment.
 */
@Component
public class SqsLogOutbox {

    private static final Logger LOG =
            LoggerFactory.getLogger(SqsLogOutbox.class);

    private final SqsClient sqs;
    private final String queueUrl;
    private final Path directory;

    private final JsonMapper json =
            JsonMapper.builder().build();

    public SqsLogOutbox(
            SqsClient sqs,
            @Value("${mmiv.log.queue-url:}") String queueUrl,
            @Value("${mmiv.log.outbox-directory:/var/lib/mmiv/log-outbox}")
            String directory) throws IOException {

        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.directory = Path.of(directory);

        if (!queueUrl.isBlank()) {

            Files.createDirectories(this.directory);

            // Apply Linux POSIX permissions only when supported.
            if (supportsPosix(this.directory)) {

                Files.setPosixFilePermissions(
                        this.directory,
                        PosixFilePermissions.fromString("rwx------")
                );

            } else {

                LOG.warn(
                        "POSIX permissions unavailable for SQS outbox directory. " +
                        "Ensure filesystem access is restricted using platform ACLs."
                );
            }

        } else {

            LOG.warn(
                    "SQS file logging disabled: " +
                    "MMIV_LOG_QUEUE_URL is not configured"
            );
        }
    }

    /**
     * Checks whether the filesystem supports POSIX permissions.
     */
    private static boolean supportsPosix(Path path)
            throws IOException {

        return Files.getFileStore(path)
                .supportsFileAttributeView("posix");
    }

    /**
     * Persists a file log event locally.
     *
     * <p>The event is written to a temporary file first,
     * synchronized to disk, then atomically renamed.
     *
     * <p>Failures are logged without changing the outcome
     * of an already completed transfer.
     */
    public void enqueue(FileLogEvent event) {

        if (queueUrl.isBlank()) {
            return;
        }

        Path pending = null;

        try {

            byte[] body =
                    json.writeValueAsBytes(event);

            // Create the temporary file using platform-compatible
            // file attributes.
            if (supportsPosix(directory)) {

                FileAttribute<?> permissions =
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString(
                                        "rw-------"
                                )
                        );

                pending = Files.createTempFile(
                        directory,
                        ".pending-",
                        ".tmp",
                        permissions
                );

            } else {

                // Windows: rely on filesystem ACLs.
                pending = Files.createTempFile(
                        directory,
                        ".pending-",
                        ".tmp"
                );
            }

            // Write the complete event to disk.
            try (FileChannel output = FileChannel.open(
                    pending,
                    StandardOpenOption.WRITE
            )) {

                ByteBuffer buffer =
                        ByteBuffer.wrap(body);

                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }

                // Ensure file contents are flushed.
                output.force(true);
            }

            Path destination =
                    directory.resolve(
                            event.eventId() + ".json"
                    );

            // Preserve atomic rename behavior.
            Files.move(
                    pending,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE
            );

            /*
             * Synchronize the directory entry on filesystems
             * that support opening directories as channels.
             *
             * Windows may not support this operation.
             */
            try (FileChannel parent = FileChannel.open(
                    directory,
                    StandardOpenOption.READ
            )) {

                parent.force(true);

            } catch (UnsupportedOperationException error) {

                LOG.debug(
                        "Directory fsync is not supported " +
                        "on this filesystem"
                );

            } catch (IOException error) {

                LOG.debug(
                        "Directory fsync unavailable: {}",
                        error.getClass().getSimpleName()
                );
            }

        } catch (IOException | RuntimeException error) {

            /*
             * Logging must not turn a completed upload/download
             * into a retryable transfer failure.
             */
            LOG.error(
                    "File log could not be persisted: " +
                    "eventId={} errorType={}",
                    event.eventId(),
                    error.getClass().getSimpleName()
            );

        } finally {

            if (pending != null) {

                try {

                    Files.deleteIfExists(pending);

                } catch (IOException error) {

                    LOG.warn(
                            "Could not remove incomplete file log"
                    );
                }
            }
        }
    }

    /**
     * Sends pending file log events to SQS.
     *
     * <p>Runs asynchronously on the scheduled worker.
     *
     * <p>Failed events remain on disk and are retried
     * during the next scheduled execution.
     */
    @Scheduled(
            fixedDelayString = "${mmiv.log.flush-delay-ms:5000}",
            initialDelay = 1000
    )
    public synchronized void flush() {

        if (queueUrl.isBlank()) {
            return;
        }

        try (var entries = Files.list(directory)) {

            var messages = entries
                    .filter(path ->
                            path.getFileName()
                                    .toString()
                                    .endsWith(".json")
                    )
                    .sorted(
                            Comparator.comparing(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                            )
                    )
                    .limit(20)
                    .toList();

            for (Path message : messages) {

                /*
                 * Preserve the original JSON and eventId
                 * across retries, including after restart.
                 */
                sqs.sendMessage(
                        SendMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .messageBody(
                                        Files.readString(message)
                                )
                                .build()
                );

                // Delete only after SQS acknowledges receipt.
                Files.delete(message);
            }

        } catch (IOException | RuntimeException error) {

            LOG.warn(
                    "SQS file logs remain pending; " +
                    "retry scheduled: errorType={}",
                    error.getClass().getSimpleName()
            );
        }
    }
}

package mmiv.transfer.logging;

import mmiv.transfer.dto.UploadResponse;
import mmiv.transfer.files.StoredFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqsLogOutboxTest {
    @TempDir Path directory;
    private final SqsClient sqs = mock(SqsClient.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private SqsLogOutbox outbox() throws Exception {
        return new SqsLogOutbox(sqs, "https://sqs.ap-southeast-1.amazonaws.com/123/mmiv-file-logs", directory.toString());
    }
    private List<Path> messages() throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(".json")).toList();
        }
    }
    @Test void uploadPersistsUtcTimesAndDoesNotSendOnTheRequestThread() throws Exception {
        var logs = new FileEventLogger(outbox());
        Instant start = Instant.now().minusSeconds(2);
        logs.upload(new UploadResponse("bucket", "folder/notes.txt", "notes.txt", "text/plain",
                10, "checksum", "file-id", "transfer-id", "PASSED", true, "Verified"), start);
        verifyNoInteractions(sqs);
        var event = json.readTree(Files.readString(messages().getFirst()));
        assertEquals("UPLOAD", event.path("operation").asText());
        assertEquals("transfer-id", event.path("transferId").asText());
        assertEquals(start, Instant.parse(event.path("startedAt").asText()));
        assertFalse(Instant.parse(event.path("completedAt").asText()).isBefore(start));
        assertEquals(10, event.path("sizeBytes").asLong());
        assertTrue(event.path("bytesServed").isNull());
    }
    @Test void sqsFailureKeepsTheSameEventForRetryAfterRestart() throws Exception {
        var original = outbox();
        new FileEventLogger(original).download("DOWNLOAD", "id",
                new StoredFile("bucket", "key", "file.txt", 10, "etag"), 10, true, null, Instant.now());
        String persisted = Files.readString(messages().getFirst());
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(new IllegalStateException("offline"));
        assertDoesNotThrow(original::flush);
        assertEquals(persisted, Files.readString(messages().getFirst()));
        reset(sqs);
        outbox().flush();
        var request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(request.capture());
        assertEquals(persisted, request.getValue().messageBody());
        assertTrue(messages().isEmpty());
    }
    @Test void failedDownloadRetainsBytesServedAndFailureOutcome() throws Exception {
        new FileEventLogger(outbox()).download("DOWNLOAD", "id",
                new StoredFile("bucket", "key", "file.txt", 10, "etag"), 4, false,
                "IOException", Instant.now());
        var event = json.readTree(Files.readString(messages().getFirst()));
        assertFalse(event.path("success").asBoolean());
        assertEquals(4, event.path("bytesServed").asLong());
        assertEquals("IOException", event.path("errorType").asText());
    }
    @Test void diskFailureDoesNotChangeAnAlreadyCompletedOperation() throws Exception {
        var original = outbox();
        Files.delete(directory);
        Files.writeString(directory, "not a directory");
        assertDoesNotThrow(() -> new FileEventLogger(original).download("DOWNLOAD", "id",
                null, 0, false, "FileStorageException", Instant.now()));
        verifyNoInteractions(sqs);
    }
}

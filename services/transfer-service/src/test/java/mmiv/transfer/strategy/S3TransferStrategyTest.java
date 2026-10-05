package mmiv.transfer.strategy;

import mmiv.transfer.dto.TransferRequest;
import mmiv.transfer.validation.TransferRequestValidator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3TransferStrategyTest {
    @TempDir Path media;
    S3Client s3;
    S3TransferStrategy strategy;
    @BeforeEach void setUp() {
        s3 = mock(S3Client.class);
        strategy = new S3TransferStrategy(s3, "media-bucket", new TransferRequestValidator(media.toString()));
    }
    @ParameterizedTest @ValueSource(strings = {"photo.png", "photo.jpg", "photo.jpeg", "clip.mp4", "notes.txt", "doc.pdf", "PHOTO.PNG", "PHOTO.JpEg"})
    void supportsExpectedExtensions(String name) {
        assertTrue(strategy.supports(new TransferRequest(name, "key", null)));
    }
    @ParameterizedTest @ValueSource(strings = {"app.exe", "archive.zip", "noextension", ".png", "photo.", "photo.png.exe"})
    void rejectsUnsupportedExtensions(String name) {
        assertFalse(strategy.supports(new TransferRequest(name, "key", null)));
    }
    @Test void uploadsExactBytesAndMetadata() throws IOException {
        byte[] bytes = {0, 1, 2, 3, -1};
        Files.write(media.resolve("photo.png"), bytes);
        var response = strategy.transfer(new TransferRequest("photo.png", "archive/photo.png", "image/png"));
        var put = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(put.capture(), body.capture());
        assertEquals("media-bucket", put.getValue().bucket());
        assertEquals("archive/photo.png", put.getValue().key());
        assertEquals("image/png", put.getValue().contentType());
        assertEquals(bytes.length, body.getValue().contentLength());
        try (var stream = body.getValue().contentStreamProvider().newStream()) {
            assertArrayEquals(bytes, stream.readAllBytes());
        }
        assertEquals("media-bucket", response.bucket());
        assertEquals("archive/photo.png", response.key());
        assertEquals("image/png", response.mediaType());
        assertEquals(bytes.length, response.sizeBytes());
    }
    @Test void detectsContentTypeWhenNotProvided() throws IOException {
        Path source = Files.writeString(media.resolve("notes.txt"), "hello");
        String detected = Files.probeContentType(source);
        var response = strategy.transfer(new TransferRequest("notes.txt", "notes.txt", null));
        assertEquals(detected == null ? "application/octet-stream" : detected, response.mediaType());
    }
    @Test void detectsContentTypeWhenBlank() throws IOException {
        Path source = Files.writeString(media.resolve("notes.txt"), "hello");
        String detected = Files.probeContentType(source);
        var response = strategy.transfer(new TransferRequest("notes.txt", "notes.txt", " "));
        assertEquals(detected == null ? "application/octet-stream" : detected, response.mediaType());
    }
    @Test void transfersEmptyFile() throws IOException {
        Files.createFile(media.resolve("empty.txt"));
        assertEquals(0, strategy.transfer(new TransferRequest("empty.txt", "empty.txt", "text/plain")).sizeBytes());
    }
    @Test void missingSourceNeverCallsS3() {
        assertThrows(IllegalArgumentException.class, () -> strategy.transfer(new TransferRequest("missing.txt", "key", null)));
        verifyNoInteractions(s3);
    }
    @Test void wrapsS3FailureAndPreservesCause() throws IOException {
        Files.writeString(media.resolve("notes.txt"), "hello");
        var failure = S3Exception.builder().message("Access denied").statusCode(403).build();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);
        var actual = assertThrows(IllegalStateException.class, () -> strategy.transfer(new TransferRequest("notes.txt", "key", "text/plain")));
        assertEquals("S3 transfer failed", actual.getMessage());
        assertSame(failure, actual.getCause());
    }
}

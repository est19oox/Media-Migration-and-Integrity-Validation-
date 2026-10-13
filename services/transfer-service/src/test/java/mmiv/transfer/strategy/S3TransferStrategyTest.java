package mmiv.transfer.strategy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import mmiv.transfer.dto.PreparedTransfer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class S3TransferStrategyTest {
  @TempDir Path directory;
  S3Client s3;
  S3TransferStrategy strategy;

  @BeforeEach
  void setUp() {
    s3 = mock(S3Client.class);
    strategy = new S3TransferStrategy(s3, "media-bucket");
  }

  @Test
  void identifiesStorageBackend() {
    assertEquals(TransferBackend.S3, strategy.backend());
  }

  private PreparedTransfer source() throws Exception {
    byte[] bytes = {0, 1, 2, 3, -1};
    Path snapshot = Files.write(directory.resolve("snapshot.tmp"), bytes);
    return new PreparedTransfer(
        snapshot,
        "photo.png",
        "migrations/assigned/photo.png",
        "image/png",
        bytes.length,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
  }

  @Test
  void storesPreparedBytesAndReturnsExactCleanupReference() throws Exception {
    var source = source();
    when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenAnswer(
            call -> {
              RequestBody body = call.getArgument(1);
              try (var input = body.contentStreamProvider().newStream()) {
                assertArrayEquals(Files.readAllBytes(source.path()), input.readAllBytes());
              }
              return PutObjectResponse.builder().eTag("etag").versionId("version").build();
            });
    var response = strategy.transfer(source);
    var put = ArgumentCaptor.forClass(PutObjectRequest.class);
    var body = ArgumentCaptor.forClass(RequestBody.class);
    verify(s3).putObject(put.capture(), body.capture());
    assertEquals("media-bucket", put.getValue().bucket());
    assertEquals(source.key(), put.getValue().key());
    assertEquals("image/png", put.getValue().contentType());
    assertEquals(source.sizeBytes(), body.getValue().optionalContentLength().orElseThrow());
    assertEquals(
        Base64.getEncoder().encodeToString(HexFormat.of().parseHex(source.sha256())),
        put.getValue().checksumSHA256());
    assertEquals("media-bucket", response.bucket());
    assertEquals(source.key(), response.key());
    assertEquals("etag", response.objectReference().eTag());
    assertEquals("version", response.objectReference().versionId());
    // The owner controls the snapshot lifetime; the storage adapter does not delete it.
    assertTrue(Files.exists(source.path()));
    source.close();
    assertFalse(Files.exists(source.path()));
  }

  @Test
  void wrapsStorageFailureAndLeavesSnapshotForOwnerCleanup() throws Exception {
    var source = source();
    var failure = S3Exception.builder().message("Access denied").statusCode(403).build();
    when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);
    var actual = assertThrows(IllegalStateException.class, () -> strategy.transfer(source));
    assertSame(failure, actual.getCause());
    assertTrue(Files.exists(source.path()));
    source.close();
    assertFalse(Files.exists(source.path()));
  }
}

package mmiv.transfer.service;

import mmiv.transfer.dto.*;
import mmiv.transfer.factory.TransferStrategyFactory;
import mmiv.transfer.integration.*;
import mmiv.transfer.strategy.S3TransferStrategy;
import mmiv.transfer.validation.TransferRequestValidator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferRetryCleanupTest {
    @TempDir Path media;
    final S3Client s3 = mock(S3Client.class);
    final IntegrityClient client = mock(IntegrityClient.class);
    TransferService service;
    @org.junit.jupiter.api.AfterEach void resetCircuit(){mmiv.resilience.DependencyCircuits.breaker("integrity","eureka:integrity-service").reset();}
    @BeforeEach void setup() {
        mmiv.resilience.DependencyCircuits.breaker("integrity","eureka:integrity-service").reset();
        var validator = new TransferRequestValidator(media.toString());
        var strategy = new S3TransferStrategy(s3, "bucket");
        service = new TransferService(new mmiv.transfer.service.TransferSourcePreparer(validator, 104857600), new TransferStrategyFactory(strategy),
                new IntegrityValidationGateway(client, 3, 0), new S3ObjectCleanup(s3, 3, 0), mock(mmiv.transfer.logging.FileEventLogger.class));
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"etag\"").versionId("version-1").build());
    }
    private IntegrityValidationResponse result(IntegrityValidationRequest request, boolean valid) {
        return new IntegrityValidationResponse(request.transferId(), request.bucket(), request.objectKey(),
                "SHA-256", request.sourceChecksum(), valid ? request.sourceChecksum() : "b".repeat(64),
                valid, valid ? "PASSED" : "FAILED");
    }
    private UploadResponse upload() {
        return service.upload(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()));
    }
    @Test void immediatePassStopsAfterFirstAttempt() {
        when(client.validate(any())).thenAnswer(call -> result(call.getArgument(0), true));
        assertTrue(upload().success());
        verify(client, times(1)).validate(any());
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void eventualPassKeepsFileAndDoesNotUploadAgain() {
        var attempts = new AtomicInteger();
        when(client.validate(any())).thenAnswer(call -> result(call.getArgument(0), attempts.incrementAndGet() == 3));
        var response = upload();
        assertTrue(response.success());
        assertEquals("PASSED", response.validationResult());
        assertEquals("NOT_REQUIRED", response.cleanupStatus());
        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        var requests = ArgumentCaptor.forClass(IntegrityValidationRequest.class);
        verify(client, times(3)).validate(requests.capture());
        for (var request : requests.getAllValues()) assertEquals(response.transferId(), request.transferId());
    }
    @Test void exhaustedMismatchDeletesExactUploadedVersion() {
        when(client.validate(any())).thenAnswer(call -> result(call.getArgument(0), false));
        var response = upload();
        assertFalse(response.success());
        assertEquals("FAILED", response.validationResult());
        assertEquals("DELETED", response.cleanupStatus());
        var request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(request.capture());
        assertEquals("version-1", request.getValue().versionId());
        assertEquals("\"etag\"", request.getValue().ifMatch());
        assertEquals(response.key(), request.getValue().key());
        verify(client, times(3)).validate(any());
        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
    @Test void exhaustedUnavailableAlsoDeletesUploadedFile() {
        when(client.validate(any())).thenThrow(mock(feign.FeignException.class));
        var response = upload();
        assertEquals("UNAVAILABLE", response.validationResult());
        assertEquals("DELETED", response.cleanupStatus());
        assertFalse(response.success());
        verify(client, times(3)).validate(any());
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void cleanupExhaustionIsReportedSeparately() {
        when(client.validate(any())).thenReturn(null);
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(503).message("Unavailable").build());
        var response = upload();
        assertEquals("UNAVAILABLE", response.validationResult());
        assertEquals("DELETE_FAILED", response.cleanupStatus());
        assertTrue(response.message().contains("manual cleanup"));
        verify(s3, times(3)).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void uploadFailureNeverValidatesOrDeletes() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(503).build());
        assertThrows(IllegalStateException.class, this::upload);
        verifyNoInteractions(client);
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void serverPathTransferUsesSameRetryAndCleanupPolicy() throws Exception {
        Files.writeString(media.resolve("notes.txt"), "hello");
        when(client.validate(any())).thenAnswer(call -> result(call.getArgument(0), false));
        var response = service.transfer(new TransferRequest("notes.txt", "server-path-key", "text/plain"));
        assertEquals("DELETED", response.cleanupStatus());
        assertEquals("FAILED", response.validationResult());
        verify(client, times(3)).validate(any());
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
    }
}

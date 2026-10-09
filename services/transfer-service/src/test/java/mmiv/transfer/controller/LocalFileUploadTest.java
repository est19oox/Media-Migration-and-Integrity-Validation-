package mmiv.transfer.controller;

import mmiv.transfer.dto.UploadSource;
import mmiv.transfer.factory.TransferStrategyFactory;
import mmiv.transfer.service.TransferService;
import mmiv.transfer.strategy.S3TransferStrategy;
import mmiv.transfer.validation.TransferRequestValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LocalFileUploadTest {
    private mmiv.transfer.service.S3ObjectCleanup cleanup;
    private mmiv.transfer.service.S3ObjectCleanup passingCleanup() {
        cleanup = mock(mmiv.transfer.service.S3ObjectCleanup.class);
        when(cleanup.delete(anyString(), anyString(), nullable(mmiv.transfer.dto.S3ObjectReference.class)))
                .thenReturn(new mmiv.transfer.service.S3ObjectCleanup.Result("DELETED", "Uploaded file deleted"));
        return cleanup;
    }
    private mmiv.transfer.integration.IntegrityValidationGateway gateway;
    private mmiv.transfer.integration.IntegrityValidationGateway passingIntegrity() {
        gateway = mock(mmiv.transfer.integration.IntegrityValidationGateway.class);
        when(gateway.validate(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new mmiv.transfer.integration.IntegrityValidationGateway.Outcome("PASSED", true, "Verified"));
        return gateway;
    }
    S3Client s3;
    S3TransferStrategy strategy;
    MockMvc mvc;

    @BeforeEach void setup() {
        s3 = mock(S3Client.class);
        var validator = new TransferRequestValidator("/unused-media-root");
        strategy = spy(new S3TransferStrategy(s3, "test-bucket", validator));
        var service = new TransferService(validator, new TransferStrategyFactory(List.of(strategy)), passingIntegrity(), passingCleanup());
        mvc = MockMvcBuilders.standaloneSetup(new TransferController(service))
                .setControllerAdvice(new TransferExceptionHandler()).build();
    }

    @Test void sendsLocalBytesToS3AndDeletesStagedFile() throws Exception {
        byte[] bytes = "local upload\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest put = call.getArgument(0);
            RequestBody body = call.getArgument(1);
            assertTrue(put.key().matches("uploads/[0-9a-f-]+/notes.txt"));
            assertEquals("test-bucket", put.bucket());
            assertEquals("text/plain", put.contentType());
            assertEquals(Base64.getEncoder().encodeToString(digest), put.checksumSHA256());
            try (var stream = body.contentStreamProvider().newStream()) {
                assertArrayEquals(bytes, stream.readAllBytes());
            }
            return PutObjectResponse.builder().build();
        });
        mvc.perform(multipart("/api/v1/transfers").file(new MockMultipartFile(
                "file", "C:\\fakepath\\notes.txt", "application/x-spoofed", bytes)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("notes.txt"))
                .andExpect(jsonPath("$.sizeBytes").value(bytes.length))
                .andExpect(jsonPath("$.sha256").value(HexFormat.of().formatHex(digest)));
        var source = ArgumentCaptor.forClass(UploadSource.class);
        verify(strategy).transferUploaded(source.capture());
        assertFalse(Files.exists(source.getValue().path()));
    }

    @Test void integrityFailureStillReturnsUploadedMetadataWithoutSuccess() throws Exception {
        when(gateway.validate(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new mmiv.transfer.integration.IntegrityValidationGateway.Outcome("FAILED", false, "Checksum mismatch"));
        mvc.perform(multipart("/api/v1/transfers").file(new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello".getBytes())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.validationResult").value("FAILED"))
                .andExpect(jsonPath("$.transferId").isNotEmpty()).andExpect(jsonPath("$.fileId").isNotEmpty());
    }

    @Test void missingFileReturns400WithoutUploading() throws Exception {
        mvc.perform(multipart("/api/v1/transfers").param("note", "missing"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("file is required"));
        verifyNoInteractions(s3);
    }

    @Test void emptyFileReturns400WithoutUploading() throws Exception {
        mvc.perform(multipart("/api/v1/transfers").file(new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0])))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(s3);
    }

    @Test void unsupportedExtensionReturns400WithoutUploading() throws Exception {
        mvc.perform(multipart("/api/v1/transfers").file(new MockMultipartFile(
                "file", "bad.exe", "text/plain", "bad".getBytes())))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(s3);
    }

    @Test void storageFailureReturns502AndDeletesStagedFile() throws Exception {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("secret storage details"));
        mvc.perform(multipart("/api/v1/transfers").file(new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello".getBytes())))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Storage transfer failed"));
        var source = ArgumentCaptor.forClass(UploadSource.class);
        verify(strategy).transferUploaded(source.capture());
        assertFalse(Files.exists(source.getValue().path()));
        verify(gateway, never()).validate(anyString(), anyString(), anyString(), anyString());
    }

    @Test void validatorRejectsOversizedFileBeforeReadingIt() throws Exception {
        var file = mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.getSize()).thenReturn(104857601L);
        assertThrows(IllegalArgumentException.class,
                () -> new TransferRequestValidator("/unused").validateUpload(file, 104857600));
        verify(file, never()).getInputStream();
    }
}

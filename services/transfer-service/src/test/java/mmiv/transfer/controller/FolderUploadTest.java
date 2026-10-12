
package mmiv.transfer.controller;

import mmiv.transfer.dto.UploadSource;
import mmiv.transfer.events.TransferEventPublisher;
import mmiv.transfer.factory.TransferStrategyFactory;
import mmiv.transfer.integration.IntegrityValidationGateway;
import mmiv.transfer.logging.FileEventLogger;
import mmiv.transfer.service.FolderUploadService;
import mmiv.transfer.service.S3ObjectCleanup;
import mmiv.transfer.service.TransferService;
import mmiv.transfer.strategy.S3TransferStrategy;
import mmiv.transfer.validation.TransferRequestValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FolderUploadTest {

    private S3ObjectCleanup cleanup;
    private IntegrityValidationGateway gateway;
    private TransferEventPublisher eventPublisher;

    private S3Client s3;
    private S3TransferStrategy strategy;
    private FolderUploadService folders;
    private MockMvc mvc;

    private S3ObjectCleanup passingCleanup() {

        cleanup = mock(S3ObjectCleanup.class);

        when(cleanup.delete(
                anyString(),
                anyString(),
                nullable(mmiv.transfer.dto.S3ObjectReference.class)
        )).thenReturn(
                new S3ObjectCleanup.Result(
                        "DELETED",
                        "Uploaded file deleted"
                )
        );

        return cleanup;
    }

    private IntegrityValidationGateway passingIntegrity() {

        gateway = mock(IntegrityValidationGateway.class);

        when(gateway.validate(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(
                new IntegrityValidationGateway.Outcome(
                        "PASSED",
                        true,
                        "Verified"
                )
        );

        return gateway;
    }

    private MockMultipartFile file(String name) {

        return new MockMultipartFile(
                "files",
                name,
                "application/octet-stream",
                "hello".getBytes()
        );
    }

    @BeforeEach
    void setup() {

        s3 = mock(S3Client.class);

        TransferRequestValidator validator =
                new TransferRequestValidator("/unused");

        strategy = spy(
                new S3TransferStrategy(
                        s3,
                        "test-bucket",
                        validator
                )
        );

        // Mock RabbitMQ publisher to isolate folder upload tests.
        eventPublisher = mock(TransferEventPublisher.class);

        TransferService transfers = new TransferService(
                validator,
                new TransferStrategyFactory(List.of(strategy)),
                passingIntegrity(),
                passingCleanup(),
                mock(FileEventLogger.class),
                eventPublisher
        );

        folders = new FolderUploadService(
                transfers,
                validator
        );

        mvc = MockMvcBuilders
                .standaloneSetup(
                        new FolderUploadController(folders)
                )
                .setControllerAdvice(
                        new TransferExceptionHandler()
                )
                .build();
    }

    @Test
    void preservesNestedPathsAndDuplicateBasenamesAndCleansTemporaryFiles()
            throws Exception {

        when(s3.putObject(
                any(PutObjectRequest.class),
                any(RequestBody.class)
        )).thenReturn(
                PutObjectResponse.builder().build()
        );

        mvc.perform(
                multipart("/api/v1/transfers/folder")
                        .file(file("a.txt"))
                        .file(file("a.txt"))
                        .param(
                                "relativePaths",
                                "album/a.txt",
                                "album/sub/deep/a.txt"
                        )
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.succeeded").value(2))
        .andExpect(jsonPath("$.failed").value(0))
        .andExpect(jsonPath("$.files[1].relativePath")
                .value("album/sub/deep/a.txt"))
        .andExpect(jsonPath("$.files[1].upload.sha256")
                .isNotEmpty());

        ArgumentCaptor<UploadSource> sources =
                ArgumentCaptor.forClass(UploadSource.class);

        verify(strategy, times(2))
                .transferUploaded(sources.capture());

        UploadSource first = sources.getAllValues().get(0);
        UploadSource second = sources.getAllValues().get(1);

        String prefix = first.key().substring(
                0,
                first.key().indexOf("album/")
        );

        assertEquals(
                prefix + "album/sub/deep/a.txt",
                second.key()
        );

        assertFalse(Files.exists(first.path()));
        assertFalse(Files.exists(second.path()));
    }

    @Test
    void countsUnconfirmedValidationAsUnsuccessfulAndContinues()
            throws Exception {

        when(gateway.validate(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(
                new IntegrityValidationGateway.Outcome(
                        "UNAVAILABLE",
                        false,
                        "Unconfirmed"
                ),
                new IntegrityValidationGateway.Outcome(
                        "PASSED",
                        true,
                        "Verified"
                )
        );

        mvc.perform(
                multipart("/api/v1/transfers/folder")
                        .file(file("a.txt"))
                        .file(file("b.txt"))
                        .param(
                                "relativePaths",
                                "album/a.txt",
                                "album/b.txt"
                        )
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.succeeded").value(1))
        .andExpect(jsonPath("$.failed").value(1))
        .andExpect(jsonPath("$.files[0].status")
                .value("UNAVAILABLE"))
        .andExpect(jsonPath("$.files[0].upload.success")
                .value(false))
        .andExpect(jsonPath("$.files[1].upload.success")
                .value(true));
    }

    @Test
    void rejectsUnsafePathsBeforeAnyS3Write()
            throws Exception {

        for (String path : List.of(
                "/album/a.txt",
                "album/../a.txt",
                "album/./a.txt",
                "album//a.txt",
                "C:\\album\\a.txt",
                "album/sub/",
                "album/evil:a.txt"
        )) {

            mvc.perform(
                    multipart("/api/v1/transfers/folder")
                            .file(file("a.txt"))
                            .param("relativePaths", path)
            )
            .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(s3);
    }

    @Test
    void rejectsBadManifestAndUnsupportedFilesBeforeUploading()
            throws Exception {

        mvc.perform(
                multipart("/api/v1/transfers/folder")
                        .file(file("a.txt"))
        )
        .andExpect(status().isBadRequest());

        mvc.perform(
                multipart("/api/v1/transfers/folder")
                        .file(file("a.txt"))
                        .file(file("bad.exe"))
                        .param(
                                "relativePaths",
                                "album/a.txt",
                                "album/bad.exe"
                        )
        )
        .andExpect(status().isBadRequest());

        mvc.perform(
                multipart("/api/v1/transfers/folder")
                        .file(file("a.txt"))
                        .file(file("a.txt"))
                        .param(
                                "relativePaths",
                                "album/a.txt",
                                "album/a.txt"
                        )
        )
        .andExpect(status().isBadRequest());

        mvc.perform(
                multipart("/api/v1/transfers/folder")
                        .file(file("a.txt"))
                        .param(
                                "relativePaths",
                                "album/b.txt"
                        )
        )
        .andExpect(status().isBadRequest());

        verifyNoInteractions(s3);
    }

    @Test
    void enforcesCountAndTotalSizeLimits() {

        ReflectionTestUtils.setField(
                folders,
                "maxFiles",
                1
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> folders.upload(
                        List.of(file("a.txt"), file("b.txt")),
                        List.of(
                                "album/a.txt",
                                "album/b.txt"
                        )
                )
        );

        ReflectionTestUtils.setField(
                folders,
                "maxFiles",
                500
        );

        ReflectionTestUtils.setField(
                folders,
                "maxTotalBytes",
                9L
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> folders.upload(
                        List.of(file("a.txt"), file("b.txt")),
                        List.of(
                                "album/a.txt",
                                "album/b.txt"
                        )
                )
        );

        verifyNoInteractions(s3);
    }

    @Test
    void reportsPartialFailureAndContinuesAndCleansTemporaryFiles()
            throws Exception {

        when(s3.putObject(
                any(PutObjectRequest.class),
                any(RequestBody.class)
        ))
        .thenThrow(
                new IllegalStateException("private details")
        )
        .thenReturn(
                PutObjectResponse.builder().build()
        );

        mvc.perform(
                multipart("/api/v1/transfers/folder")
                        .file(file("a.txt"))
                        .file(file("b.txt"))
                        .param(
                                "relativePaths",
                                "album/a.txt",
                                "album/sub/b.txt"
                        )
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.succeeded").value(1))
        .andExpect(jsonPath("$.failed").value(1))
        .andExpect(jsonPath("$.files[0].status")
                .value("FAILED"))
        .andExpect(jsonPath("$.files[0].error")
                .value("File transfer failed"))
        .andExpect(jsonPath("$.files[1].status")
                .value("PASSED"));

        ArgumentCaptor<UploadSource> sources =
                ArgumentCaptor.forClass(UploadSource.class);

        verify(strategy, times(2))
                .transferUploaded(sources.capture());

        for (UploadSource source : sources.getAllValues()) {
            assertFalse(Files.exists(source.path()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "deck.ppt",
            "deck.pptx",
            "report.doc",
            "report.docx"
    })
    void uploadsOfficeFileWithItsNestedPath(String name)
            throws Exception {

        mvc.perform(
                multipart("/api/v1/transfers/folder")
                        .file(file(name))
                        .param(
                                "relativePaths",
                                "Office/sub/" + name
                        )
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.succeeded").value(1))
        .andExpect(jsonPath("$.failed").value(0))
        .andExpect(jsonPath("$.files[0].relativePath")
                .value("Office/sub/" + name));

        ArgumentCaptor<UploadSource> source =
                ArgumentCaptor.forClass(UploadSource.class);

        verify(strategy)
                .transferUploaded(source.capture());

        assertEquals(
                new TransferRequestValidator("/unused")
                        .uploadContentType(name),
                source.getValue().contentType()
        );
    }
}

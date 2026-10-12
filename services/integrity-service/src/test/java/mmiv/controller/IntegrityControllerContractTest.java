
package mmiv.controller;

import mmiv.dto.ValidationResponse;
import mmiv.service.ChecksumService;
import mmiv.service.IntegrityValidationService;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IntegrityControllerContractTest {

    @Test
    void echoesTransferIdAndValidationResult() throws Exception {

        IntegrityValidationService service =
                mock(IntegrityValidationService.class);

        String checksum = "a".repeat(64);

        when(service.validate("bucket", "key", checksum))
                .thenReturn(
                        new ValidationResponse(
                                "bucket",
                                "key",
                                "SHA-256",
                                checksum,
                                checksum,
                                true
                        )
                );

        var controller = new IntegrityController(
                mock(ChecksumService.class),
                service
        );

        var mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .build();

        mvc.perform(
                post("/api/integrity/validate")
                        .contentType("application/json")
                        .content("""
                                {
                                  "transferId": "transfer-1",
                                  "bucket": "bucket",
                                  "objectKey": "key",
                                  "sourceChecksum": "%s"
                                }
                                """.formatted(checksum))
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transferId")
                .value("transfer-1"))
        .andExpect(jsonPath("$.validationResult")
                .value("PASSED"))
        .andExpect(jsonPath("$.valid")
                .value(true));

        verify(service).validate("bucket", "key", checksum);
    }

    @Test
    void invalidChecksumIsRejectedBeforeReadingS3()
            throws Exception {

        IntegrityValidationService service =
                mock(IntegrityValidationService.class);

        var controller = new IntegrityController(
                mock(ChecksumService.class),
                service
        );

        var mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .build();

        mvc.perform(
                post("/api/integrity/validate")
                        .contentType("application/json")
                        .content("""
                                {
                                  "bucket": "bucket",
                                  "objectKey": "key",
                                  "sourceChecksum": "invalid"
                                }
                                """)
        )
        .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}

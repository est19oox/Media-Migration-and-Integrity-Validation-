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
    @Test void echoesTransferIdAndValidationResult() throws Exception {
        var service = mock(IntegrityValidationService.class);
        String checksum = "a".repeat(64);
        when(service.validate("bucket", "key", checksum)).thenReturn(
                new ValidationResponse("bucket", "key", "SHA-256", checksum, checksum, true));
        var mvc = MockMvcBuilders.standaloneSetup(new IntegrityController(mock(ChecksumService.class), service, mock(mmiv.integration.IntegrityMetadataGuard.class), mock(mmiv.events.EventPublisher.class))).setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(post("/api/integrity/validate").contentType("application/json")
                .content("{\"transferId\":\"12345678-1234-4234-8234-123456789012\",\"bucket\":\"bucket\",\"objectKey\":\"key\",\"sourceChecksum\":\"" + checksum + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transferId").value("12345678-1234-4234-8234-123456789012"))
                .andExpect(jsonPath("$.validationResult").value("PASSED"));
    }
    @Test void invalidChecksumIsRejectedBeforeReadingS3() throws Exception {
        var service = mock(IntegrityValidationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new IntegrityController(mock(ChecksumService.class), service, mock(mmiv.integration.IntegrityMetadataGuard.class), mock(mmiv.events.EventPublisher.class))).setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(post("/api/integrity/validate").contentType("application/json")
                .content("{\"bucket\":\"bucket\",\"objectKey\":\"key\",\"sourceChecksum\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

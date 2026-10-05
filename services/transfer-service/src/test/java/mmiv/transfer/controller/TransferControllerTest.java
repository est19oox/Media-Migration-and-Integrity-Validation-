package mmiv.transfer.controller;

import mmiv.transfer.dto.*;
import mmiv.transfer.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TransferControllerTest {
    TransferService service;
    MockMvc mvc;
    @BeforeEach void setUp() {
        service = mock(TransferService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TransferController(service))
                .setControllerAdvice(new TransferExceptionHandler()).build();
    }
    @Test void returnsSuccessfulTransferAndPassesRequestFields() throws Exception {
        when(service.transfer(any())).thenReturn(new TransferResponse("bucket", "archive/photo.png", "image/png", 123));
        mvc.perform(post("/api/v1/transfers").contentType("application/json")
                .content("{\"sourcePath\":\"photo.png\",\"destinationKey\":\"archive/photo.png\",\"contentType\":\"image/png\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bucket").value("bucket"))
                .andExpect(jsonPath("$.key").value("archive/photo.png"))
                .andExpect(jsonPath("$.mediaType").value("image/png"))
                .andExpect(jsonPath("$.sizeBytes").value(123));
        var request = ArgumentCaptor.forClass(TransferRequest.class);
        verify(service).transfer(request.capture());
        assertEquals(new TransferRequest("photo.png", "archive/photo.png", "image/png"), request.getValue());
    }
    @Test void returns400ForValidationFailure() throws Exception {
        when(service.transfer(any())).thenThrow(new IllegalArgumentException("sourcePath is required"));
        mvc.perform(post("/api/v1/transfers").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("sourcePath is required"));
    }
    @Test void returns502WithoutLeakingStorageDetails() throws Exception {
        when(service.transfer(any())).thenThrow(new IllegalStateException("secret internal storage details"));
        mvc.perform(post("/api/v1/transfers").contentType("application/json").content("{}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error").value("Storage transfer failed"));
    }
    @Test void malformedJsonNeverCallsService() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType("application/json").content("{broken"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void missingBodyNeverCallsService() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType("application/json"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void unsupportedContentTypeNeverCallsService() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType("text/plain").content("sample.txt"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }
    @Test void unsupportedHttpMethodNeverCallsService() throws Exception {
        mvc.perform(get("/api/v1/transfers")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
}

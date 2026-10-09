package mmiv.transfer.integration;

import feign.FeignException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrityValidationGatewayTest {
    private final IntegrityClient client = mock(IntegrityClient.class);
    private final IntegrityValidationGateway gateway = new IntegrityValidationGateway(client, 1, 0);
    private final String checksum = "a".repeat(64);
    private final IntegrityValidationRequest request = new IntegrityValidationRequest("transfer-1", "bucket", "key", checksum);
    private IntegrityValidationGateway.Outcome validate() {
        return gateway.validate("transfer-1", "bucket", "key", checksum);
    }
    @Test void onlyMatchingChecksumsAndCorrelationReportSuccess() {
        when(client.validate(request)).thenReturn(new IntegrityValidationResponse(
                "transfer-1", "bucket", "key", "SHA-256", checksum, checksum, true, "PASSED"));
        assertTrue(validate().success());
        assertEquals("PASSED", validate().validationResult());
        verify(client, times(2)).validate(request);
    }
    @Test void mismatchIsACompletedValidationFailure() {
        when(client.validate(request)).thenReturn(new IntegrityValidationResponse(
                "transfer-1", "bucket", "key", "SHA-256", checksum, "b".repeat(64), false, "FAILED"));
        assertFalse(validate().success());
        assertEquals("FAILED", validate().validationResult());
    }
    @Test void timeoutOrHttpFailureDoesNotPretendChecksumMismatch() {
        when(client.validate(request)).thenThrow(mock(FeignException.class));
        assertEquals("UNAVAILABLE", validate().validationResult());
        assertFalse(validate().success());
    }
    @Test void mismatchedTransferIdIsRejected() {
        when(client.validate(request)).thenReturn(new IntegrityValidationResponse(
                "wrong-id", "bucket", "key", "SHA-256", checksum, checksum, true, "PASSED"));
        assertEquals("UNAVAILABLE", validate().validationResult());
    }
    @Test void malformedOrContradictoryResponsesNeverReportSuccess() {
        for (var response : java.util.List.of(
                new IntegrityValidationResponse("transfer-1", "bucket", "wrong-key", "SHA-256", checksum, checksum, true, "PASSED"),
                new IntegrityValidationResponse("transfer-1", "bucket", "key", "SHA-256", checksum, checksum, null, "PASSED"),
                new IntegrityValidationResponse("transfer-1", "bucket", "key", "SHA-256", checksum, "b".repeat(64), true, "PASSED"),
                new IntegrityValidationResponse("transfer-1", "bucket", "key", "SHA-256", checksum, checksum, true, "FAILED"))) {
            when(client.validate(request)).thenReturn(response);
            assertEquals("UNAVAILABLE", validate().validationResult());
            assertFalse(validate().success());
        }
        when(client.validate(request)).thenReturn(null);
        assertFalse(validate().success());
    }
}

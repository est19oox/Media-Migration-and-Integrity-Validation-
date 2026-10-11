package mmiv.transfer.files;

import mmiv.transfer.controller.FileController;
import mmiv.transfer.controller.TransferExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileReadTest {
    private S3Client s3;
    private MockMvc mvc;
    private mmiv.transfer.logging.FileEventLogger logs;
    private final String key = "uploads/test/notes.txt";
    private final String url = "/api/v1/files/" + FileIds.encode(key);

    @BeforeEach void setup() {
        s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(10L).eTag("\"version\"").build());
        logs = mock(mmiv.transfer.logging.FileEventLogger.class);
        mvc = MockMvcBuilders.standaloneSetup(new FileController(new FileReadService(s3, "test-bucket"), logs))
                .setControllerAdvice(new TransferExceptionHandler()).build();
    }
    private void seedContent(String bytes) {
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().build(), new ByteArrayInputStream(bytes.getBytes(StandardCharsets.UTF_8))));
    }
    @Test void downloadsExactBytesFromConfiguredBucket() throws Exception {
        seedContent("0123456789");
        mvc.perform(get(url + "/download")).andExpect(status().isOk())
                .andExpect(content().string("0123456789"))
                .andExpect(header().string("Content-Length", "10"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        verify(logs).download(eq("DOWNLOAD"), eq(FileIds.encode(key)), any(StoredFile.class),
                eq(10L), eq(true), isNull(), any(java.time.Instant.class));
        verify(s3).getObject(argThat((GetObjectRequest request) -> request.bucket().equals("test-bucket")
                && request.key().equals(key) && request.ifMatch().equals("\"version\"") && request.range() == null));
    }
    @Test void rangeUsesS3RangeAndReturns206() throws Exception {
        seedContent("2345");
        mvc.perform(get(url + "/content").header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent()).andExpect(content().string("2345"))
                .andExpect(header().string("Content-Range", "bytes 2-5/10"))
                .andExpect(header().string("Content-Length", "4"));
        verify(s3).getObject(argThat((GetObjectRequest request) -> "bytes=2-5".equals(request.range())));
        verify(logs).download(eq("PREVIEW"), eq(FileIds.encode(key)), any(StoredFile.class),
                eq(4L), eq(true), isNull(), any(java.time.Instant.class));
    }
    @Test void headDoesNotFetchObjectBody() throws Exception {
        mvc.perform(head(url + "/content").header("Range", "bytes=0-3"))
                .andExpect(status().isOk()).andExpect(content().string(""))
                .andExpect(header().string("Content-Length", "10"));
        verify(s3, never()).getObject(any(GetObjectRequest.class));
        verifyNoInteractions(logs);
    }
    @Test void staleIfRangeReturnsFullBody() throws Exception {
        seedContent("0123456789");
        mvc.perform(get(url + "/content").header("Range", "bytes=0-3").header("If-Range", "\"old\""))
                .andExpect(status().isOk()).andExpect(content().string("0123456789"));
        verify(s3).getObject(argThat((GetObjectRequest request) -> request.range() == null));
    }
    @Test void invalidRangeReturns416WithoutReadingBody() throws Exception {
        mvc.perform(get(url + "/content").header("Range", "bytes=10-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */10"));
        verify(s3, never()).getObject(any(GetObjectRequest.class));
    }
    @Test void missingFileMapsTo404() throws Exception {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        mvc.perform(get(url + "/content")).andExpect(status().isNotFound());
    }
    @Test void getFailureOccursBeforeFileHeadersCommit() throws Exception {
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(412).build());
        mvc.perform(get(url + "/content")).andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error").value("File changed; retry the request"));
    }
    @Test void invalidIdDoesNotAccessS3() throws Exception {
        mvc.perform(get("/api/v1/files/invalid!/content")).andExpect(status().isBadRequest());
        verifyNoInteractions(s3);
    }
    @Test void idsRoundTripUnicodeButRejectNoncanonicalAndUnsafeKeys() {
        String unicode = "uploads/id/照片.png";
        assertEquals(unicode, FileIds.decode(FileIds.encode(unicode)));
        assertThrows(IllegalArgumentException.class, () -> FileIds.decode("AA"));
        assertThrows(IllegalArgumentException.class, () -> FileIds.decode("YQ=="));
        assertThrows(IllegalArgumentException.class, () -> FileIds.encode("/absolute"));
    }
    @Test void parsesClosedOpenAndSuffixRangesAndRejectsInvalidRanges() {
        assertEquals(new ByteRange(2, 5), ByteRange.parse("bytes=2-5", 10));
        assertEquals(new ByteRange(2, 9), ByteRange.parse("bytes=2-", 10));
        assertEquals(new ByteRange(7, 9), ByteRange.parse("bytes=-3", 10));
        assertEquals(new ByteRange(0, 9), ByteRange.parse("bytes=-20", 10));
        assertEquals(new ByteRange(2, 9), ByteRange.parse("bytes=2-99", 10));
        for (String value : new String[]{"bytes=-0", "bytes=10-", "bytes=5-2", "bytes=0-1,4-5", "bytes=999999999999999999999-"})
            assertThrows(FileRangeException.class, () -> ByteRange.parse(value, 10));
        assertThrows(FileRangeException.class, () -> ByteRange.parse("bytes=0-", 0));
    }
}

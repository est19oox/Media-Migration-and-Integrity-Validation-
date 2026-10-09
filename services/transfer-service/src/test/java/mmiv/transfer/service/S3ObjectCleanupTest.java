package mmiv.transfer.service;

import mmiv.transfer.dto.S3ObjectReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3ObjectCleanupTest {
    final S3Client s3 = mock(S3Client.class);
    final S3ObjectCleanup cleanup = new S3ObjectCleanup(s3, 3, 0);
    final S3ObjectReference reference = new S3ObjectReference("\"etag\"", null);
    @Test void unversionedDeletionIsConditionalAndRetriesTransientErrors() {
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(503).build())
                .thenReturn(DeleteObjectResponse.builder().build());
        assertEquals("DELETED", cleanup.delete("bucket", "key", reference).status());
        var request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3, times(2)).deleteObject(request.capture());
        assertEquals("\"etag\"", request.getValue().ifMatch());
        assertNull(request.getValue().versionId());
        assertEquals(request.getAllValues().get(0), request.getAllValues().get(1));
    }
    @Test void changedObjectIsNeverDeletedUnconditionally() {
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(412).build());
        assertEquals("DELETE_FAILED", cleanup.delete("bucket", "key", reference).status());
        verify(s3, times(1)).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void missingIdentityNeverDeletesAnUnknownObject() {
        assertEquals("DELETE_FAILED", cleanup.delete("bucket", "key", null).status());
        assertEquals("DELETE_FAILED", cleanup.delete("bucket", "key", new S3ObjectReference("*", null)).status());
        verifyNoInteractions(s3);
    }
    @Test void accessDeniedDoesNotPretendSuccessOrRetry() {
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(403).build());
        assertEquals("DELETE_FAILED", cleanup.delete("bucket", "key", reference).status());
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void alreadyAbsentObjectCompletesCleanup() {
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        assertEquals("DELETED", cleanup.delete("bucket", "key", reference).status());
    }
}

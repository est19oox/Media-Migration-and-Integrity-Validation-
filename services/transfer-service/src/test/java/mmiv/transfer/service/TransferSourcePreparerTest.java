package mmiv.transfer.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import mmiv.transfer.dto.TransferRequest;
import mmiv.transfer.validation.TransferRequestValidator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferSourcePreparerTest {
    @TempDir Path media;
    TransferSourcePreparer preparer;
    @BeforeEach void setup() {
        preparer = new TransferSourcePreparer(new TransferRequestValidator(media.toString()), 32);
    }
    @ParameterizedTest
    @ValueSource(strings = {"photo.png", "photo.jpg", "photo.jpeg", "clip.mp4", "audio.mp3", "notes.txt",
            "doc.pdf", "PHOTO.PNG", "PHOTO.JpEg", "deck.ppt", "deck.pptx", "report.doc", "report.docx"})
    void localMediaValidationAndTypeInference(String name) throws Exception {
        Files.writeString(media.resolve(name), "hello");
        try (var source = preparer.prepare(new TransferRequest(name, "assigned/key", null))) {
            assertEquals(new TransferRequestValidator(media.toString()).uploadContentType(name), source.contentType());
            assertEquals(5, source.sizeBytes());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"app.exe", "archive.zip", "noextension", ".png", "photo.", "photo.png.exe"})
    void unsupportedLocalMediaCannotBecomePreparedTransfer(String name) throws Exception {
        Files.writeString(media.resolve(name), "hello");
        assertThrows(IllegalArgumentException.class,
                () -> preparer.prepare(new TransferRequest(name, "assigned/key", "text/plain")));
    }
    @Test void bothSourcesPrepareIdenticalBytesSizeAndDigest() throws Exception {
        byte[] bytes = new byte[32];
        Files.write(media.resolve("notes.txt"), bytes);
        var local = preparer.prepare(new TransferRequest("notes.txt", "assigned/key", "text/plain"));
        var upload = preparer.prepare(new MockMultipartFile("file", "notes.txt", "spoofed/type", bytes), "notes.txt", "assigned/key");
        try (local; upload) {
            assertEquals(local.sha256(), upload.sha256());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), local.sha256());
            assertEquals(32, upload.sizeBytes());
            assertEquals("text/plain", upload.contentType());
            assertArrayEquals(Files.readAllBytes(local.path()), Files.readAllBytes(upload.path()));
            Files.writeString(media.resolve("notes.txt"), "changed source");
            assertArrayEquals(bytes, Files.readAllBytes(local.path()));
        }
        assertFalse(Files.exists(local.path()));
        assertFalse(Files.exists(upload.path()));
        assertTrue(Files.exists(media.resolve("notes.txt")));
    }
    @Test void localEmptyOversizedAndMissingFilesAreRejected() throws Exception {
        Files.createFile(media.resolve("empty.txt"));
        Files.write(media.resolve("big.txt"), new byte[33]);
        for (String name : new String[] {"empty.txt", "big.txt", "missing.txt"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> preparer.prepare(new TransferRequest(name, "assigned/key", null)));
        }
    }
    private MultipartFile upload(InputStream input) throws IOException {
        var file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("notes.txt");
        when(file.getSize()).thenReturn(1L); // Deliberately smaller than the actual stream.
        when(file.getInputStream()).thenReturn(input);
        return file;
    }
    private Set<Path> stagedFiles() throws IOException {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.getFileName().toString().startsWith("mmiv-transfer-"))
                    .collect(Collectors.toSet());
        }
    }
    @Test void actualStreamSizeIsEnforcedAndPartialSnapshotIsRemoved() throws Exception {
        Set<Path> before = stagedFiles();
        var input = spy(new ByteArrayInputStream(new byte[33]));
        assertThrows(IllegalArgumentException.class,
                () -> preparer.prepare(upload(input), "notes.txt", "assigned/key"));
        verify(input).close();
        assertEquals(before, stagedFiles());
    }
    @Test void readFailureClosesInputAndRemovesPartialSnapshot() throws Exception {
        Set<Path> before = stagedFiles();
        var input = mock(InputStream.class);
        when(input.read(any(byte[].class))).thenReturn(1).thenThrow(new IOException("read failed"));
        assertThrows(IllegalStateException.class,
                () -> preparer.prepare(upload(input), "notes.txt", "assigned/key"));
        verify(input).close();
        assertEquals(before, stagedFiles());
    }
    @Test void inputCloseFailureDoesNotLeakCompletedSnapshot() throws Exception {
        Set<Path> before = stagedFiles();
        var input = spy(new ByteArrayInputStream(new byte[] {1}));
        doThrow(new IOException("close failed")).when(input).close();
        assertThrows(IllegalStateException.class,
                () -> preparer.prepare(upload(input), "notes.txt", "assigned/key"));
        assertEquals(before, stagedFiles());
    }
    @Test void mismatchedAssignedNameIsRejectedBeforeReading() throws Exception {
        var file = upload(new ByteArrayInputStream(new byte[] {1}));
        assertThrows(IllegalArgumentException.class,
                () -> preparer.prepare(file, "different.txt", "assigned/key"));
        verify(file, never()).getInputStream();
    }
}

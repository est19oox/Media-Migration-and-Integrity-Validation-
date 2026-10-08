package mmiv.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class ChecksumServiceTest {

    private final ChecksumService checksumService =
            new ChecksumService();

    @Test
    void shouldCalculateCorrectSHA256() throws Exception {

        byte[] content =
                "Hello MMIV".getBytes(StandardCharsets.UTF_8);

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "test.txt",
                        "text/plain",
                        content
                );

        String actual =
                checksumService.calculateSHA256(file);

        String expected =
                HexFormat.of().formatHex(
                        MessageDigest
                                .getInstance("SHA-256")
                                .digest(content)
                );

        assertEquals(expected, actual);
    }

    @Test
    void sameFileShouldProduceSameChecksum() throws Exception {

        byte[] content =
                "MMIV integrity test"
                        .getBytes(StandardCharsets.UTF_8);

        MockMultipartFile file1 =
                new MockMultipartFile(
                        "file",
                        "file1.txt",
                        "text/plain",
                        content
                );

        MockMultipartFile file2 =
                new MockMultipartFile(
                        "file",
                        "file2.txt",
                        "text/plain",
                        content
                );

        String checksum1 =
                checksumService.calculateSHA256(file1);

        String checksum2 =
                checksumService.calculateSHA256(file2);

        assertEquals(checksum1, checksum2);
    }

    @Test
    void differentFilesShouldProduceDifferentChecksums()
            throws Exception {

        MockMultipartFile file1 =
                new MockMultipartFile(
                        "file",
                        "file1.txt",
                        "text/plain",
                        "Original File".getBytes(StandardCharsets.UTF_8)
                );

        MockMultipartFile file2 =
                new MockMultipartFile(
                        "file",
                        "file2.txt",
                        "text/plain",
                        "Corrupted File".getBytes(StandardCharsets.UTF_8)
                );

        String checksum1 =
                checksumService.calculateSHA256(file1);

        String checksum2 =
                checksumService.calculateSHA256(file2);

        assertNotEquals(checksum1, checksum2);
    }
}
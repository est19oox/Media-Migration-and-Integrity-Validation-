package com.media.migration.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TransferServiceTest {
    @TempDir Path tempDir;

    @Test
    void transfersFileAndValidatesIntegrity() throws Exception {
        Path source = tempDir.resolve("source.mp4");
        Path destination = tempDir.resolve("migrated/source.mp4");
        Files.writeString(source, "media-content");

        TransferResponse response = new TransferService().transfer(
                source.toString(), destination.toString());

        assertTrue(response.integrityValid());
        assertEquals(Files.size(source), response.bytesTransferred());
        assertEquals(Files.readString(source), Files.readString(destination));
    }
}

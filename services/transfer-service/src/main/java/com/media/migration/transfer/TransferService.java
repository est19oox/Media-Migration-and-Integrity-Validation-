package com.media.migration.transfer;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class TransferService {

    public TransferResponse transfer(String sourcePath, String destinationPath) {
        Path source = Path.of(sourcePath).normalize();
        Path destination = Path.of(destinationPath).normalize();

        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Source media file does not exist: " + source);
        }

        try {
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            long bytes = copy(source, destination);
            String sourceHash = sha256(source);
            String destinationHash = sha256(destination);

            return new TransferResponse(
                    source.toString(),
                    destination.toString(),
                    bytes,
                    sourceHash,
                    destinationHash,
                    sourceHash.equals(destinationHash)
            );
        } catch (IOException e) {
            throw new IllegalStateException("Media transfer failed", e);
        }
    }

    private long copy(Path source, Path destination) throws IOException {
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[1024 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
            return total;
        }
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest.digest()) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}

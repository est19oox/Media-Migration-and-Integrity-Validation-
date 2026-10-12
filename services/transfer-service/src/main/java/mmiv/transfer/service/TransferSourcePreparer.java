package mmiv.transfer.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import mmiv.transfer.dto.PreparedTransfer;
import mmiv.transfer.dto.TransferRequest;
import mmiv.transfer.validation.TransferRequestValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Validates and stages either input as one bounded, checksum-stable byte snapshot. */
@Component
public class TransferSourcePreparer {
    private final TransferRequestValidator validator;
    private final long maxBytes;

    public TransferSourcePreparer(TransferRequestValidator validator,
            @Value("${mmiv.upload.max-size-bytes:104857600}") long maxBytes) {
        if (maxBytes <= 0) throw new IllegalArgumentException("Transfer size limit must be positive");
        this.validator = validator;
        this.maxBytes = maxBytes;
    }

    public String validateUpload(MultipartFile file) {
        return validator.validateUpload(file, maxBytes);
    }

    public PreparedTransfer prepare(TransferRequest request) {
        Path source = validator.validate(request);
        String name = source.getFileName().toString();
        String inferredType = validator.uploadContentType(name);
        String contentType = request.contentType() == null || request.contentType().isBlank()
                ? inferredType : request.contentType();
        try {
            return stage(Files.newInputStream(source), name, request.destinationKey(), contentType);
        } catch (IOException error) {
            throw new IllegalStateException("Transfer source preparation failed", error);
        }
    }

    public PreparedTransfer prepare(MultipartFile file, String fileName, String key) {
        String validatedName = validateUpload(file);
        if (!validatedName.equals(fileName)) {
            throw new IllegalArgumentException("Assigned file name must match the uploaded file");
        }
        if (key == null || key.isBlank() || key.startsWith("/")) {
            throw new IllegalArgumentException("Assigned destination key must be relative and nonempty");
        }
        try {
            return stage(file.getInputStream(), fileName, key, validator.uploadContentType(fileName));
        } catch (IOException error) {
            throw new IllegalStateException("Transfer source preparation failed", error);
        }
    }

    private PreparedTransfer stage(InputStream source, String name, String key, String contentType) {
        Path temporary = null;
        boolean prepared = false;
        try (source) {
            temporary = Files.createTempFile("mmiv-transfer-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (count > maxBytes - size) {
                        throw new IllegalArgumentException("File exceeds the transfer size limit");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    size += count;
                }
            }
            if (size == 0) throw new IllegalArgumentException("File must not be empty");
            var result = new PreparedTransfer(temporary, name, key, contentType, size,
                    HexFormat.of().formatHex(digest.digest()));
            prepared = true;
            return result;
        } catch (IOException | NoSuchAlgorithmException error) {
            prepared = false;
            throw new IllegalStateException("Transfer source preparation failed", error);
        } finally {
            if (!prepared && temporary != null) {
                new PreparedTransfer(temporary, name, key, contentType, 0, null).close();
            }
        }
    }
}

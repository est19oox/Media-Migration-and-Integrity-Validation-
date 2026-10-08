package mmiv.transfer.service;

import mmiv.transfer.dto.TransferRequest;
import mmiv.transfer.dto.TransferResponse;
import mmiv.transfer.factory.TransferStrategyFactory;
import mmiv.transfer.strategy.TransferStrategy;
import mmiv.transfer.validation.TransferRequestValidator;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import mmiv.transfer.dto.UploadSource;
import mmiv.transfer.dto.UploadResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TransferService {
    private final TransferRequestValidator validator;
    private final TransferStrategyFactory factory;
    @Value("${mmiv.upload.max-size-bytes:104857600}")
    private long maxUploadBytes = 104857600;
    // Future: import and constructor-inject the fully commented gateway.
    // private final IntegrityValidationGateway integrityValidationGateway;

    public TransferService(TransferRequestValidator validator, TransferStrategyFactory factory) {
        this.validator = validator;
        this.factory = factory;
    }

    public TransferResponse transfer(TransferRequest request) {
        validator.validate(request);
        TransferStrategy strategy = factory.getStrategy(request);
        return strategy.transfer(request);
    }

    public UploadResponse upload(MultipartFile file) {
        String fileName = validator.validateUpload(file, maxUploadBytes);
        return uploadToKey(file, fileName, "uploads/" + UUID.randomUUID() + "/" + fileName);
    }

    UploadResponse uploadToKey(MultipartFile file, String fileName, String key) {
        String contentType = validator.uploadContentType(fileName);
        TransferStrategy strategy = factory.getStrategy(new TransferRequest(fileName, key, contentType));
        Path temporary = null;
        try {
            temporary = Files.createTempFile("mmiv-upload-", ".tmp");
            file.transferTo(temporary);
            long actualSize = Files.size(temporary);
            if (actualSize == 0 || actualSize > maxUploadBytes) {
                throw new IllegalArgumentException("File must be nonempty and within the upload size limit");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(temporary), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            UploadResponse response = strategy.transferUploaded(new UploadSource(
                    temporary, fileName, key, contentType, actualSize, sha256));
            // Future API call via Feign + Eureka. This is NOT executed:
            // IntegrityValidationResponse validation = integrityValidationGateway.validate(response);
            // Persist a pending validation job and handle API failures/retries before enabling.
            // S3 upload success does not mean the Integrity Service has validated the file.
            return response;
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Upload processing failed", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) {
                    org.slf4j.LoggerFactory.getLogger(TransferService.class)
                            .warn("Could not delete upload temporary file");
                }
            }
        }
    }
}

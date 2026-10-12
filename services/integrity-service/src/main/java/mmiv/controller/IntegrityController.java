
package mmiv.controller;

import mmiv.dto.ChecksumResponse;
import mmiv.dto.ValidationRequest;
import mmiv.dto.ValidationResponse;
import mmiv.service.ChecksumService;
import mmiv.service.IntegrityValidationService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/integrity")
public class IntegrityController {

    private static final Pattern SHA256_PATTERN =
            Pattern.compile("^[a-fA-F0-9]{64}$");

    private final ChecksumService checksumService;
    private final IntegrityValidationService integrityValidationService;

    public IntegrityController(
            ChecksumService checksumService,
            IntegrityValidationService integrityValidationService) {

        this.checksumService = checksumService;
        this.integrityValidationService = integrityValidationService;
    }

    @PostMapping("/checksum")
    public ResponseEntity<ChecksumResponse> calculateChecksum(
            @RequestParam("file") MultipartFile file) throws IOException {

        String checksum = checksumService.calculateSHA256(file);

        ChecksumResponse response = new ChecksumResponse(
                file.getOriginalFilename(),
                file.getSize(),
                "SHA-256",
                checksum
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/validate")
    public ResponseEntity<ValidationResponse> validate(
            @RequestBody ValidationRequest request) throws IOException {

        if (request == null
                || request.bucket() == null
                || request.bucket().isBlank()
                || request.objectKey() == null
                || request.objectKey().isBlank()
                || request.sourceChecksum() == null
                || !SHA256_PATTERN.matcher(request.sourceChecksum()).matches()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid validation request"
            );
        }

        ValidationResponse result =
                integrityValidationService.validate(
                        request.bucket(),
                        request.objectKey(),
                        request.sourceChecksum()
                );

        if (result == null) {
            throw new IllegalStateException(
                    "Integrity validation returned no result"
            );
        }

        String validationResult =
                result.valid() ? "PASSED" : "FAILED";

        ValidationResponse response = new ValidationResponse(
                result.bucket(),
                result.objectKey(),
                result.algorithm(),
                result.sourceChecksum(),
                result.destinationChecksum(),
                result.valid(),
                request.transferId(),
                validationResult
        );

        return ResponseEntity.ok(response);
    }
}


package mmiv.controller;

import mmiv.dto.ChecksumResponse;
import mmiv.dto.ValidationRequest;
import mmiv.dto.ValidationResponse;
import mmiv.service.ChecksumService;
import mmiv.service.IntegrityValidationService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/integrity")
public class IntegrityController {

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

        ValidationResponse response =
                integrityValidationService.validate(
                        request.bucket(),
                        request.objectKey(),
                        request.sourceChecksum()
                );

        return ResponseEntity.ok(response);
    }
}

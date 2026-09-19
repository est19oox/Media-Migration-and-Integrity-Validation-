package com.media.migration.transfer;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        TransferResponse response = transferService.transfer(
                request.sourcePath(), request.destinationPath());

        return response.integrityValid()
                ? ResponseEntity.ok(response)
                : ResponseEntity.internalServerError().body(response);
    }
}

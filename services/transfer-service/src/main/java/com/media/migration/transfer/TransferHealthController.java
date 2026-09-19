package com.media.migration.transfer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class TransferHealthController {
    @GetMapping
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "transfer-service");
    }
}

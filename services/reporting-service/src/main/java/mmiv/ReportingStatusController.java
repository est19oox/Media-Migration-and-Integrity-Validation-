package mmiv;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
class ReportingStatusController {

    @GetMapping("/status")
    ReportingStatus status() {
        return new ReportingStatus("reporting-service", "UP");
    }

    record ReportingStatus(String service, String status) {
    }
}

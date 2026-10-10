package mmiv.user_access_service.controller;

import jakarta.validation.Valid;
import mmiv.user_access_service.dto.AccessCheckRequest;
import mmiv.user_access_service.dto.AccessCheckResponse;
import mmiv.user_access_service.service.AccessService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/access")
public class AccessController {

  private final AccessService accessService;

  public AccessController(AccessService accessService) {
    this.accessService = accessService;
  }

  @PostMapping("/check")
  public AccessCheckResponse checkAccess(@Valid @RequestBody AccessCheckRequest request) {

    return accessService.checkAccess(request);
  }
}

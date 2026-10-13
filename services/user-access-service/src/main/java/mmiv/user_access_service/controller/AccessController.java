package mmiv.user_access_service.controller;

import jakarta.validation.Valid;
import mmiv.user_access_service.dto.AccessCheckRequest;
import mmiv.user_access_service.dto.AccessCheckResponse;
import mmiv.user_access_service.dto.AccessibleDestinationsResponse;
import mmiv.user_access_service.service.AccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/access")
public class AccessController {

  private final AccessService accessService;
  private final mmiv.user_access_service.repository.FolderRepository folders;

  public AccessController(AccessService accessService,mmiv.user_access_service.repository.FolderRepository folders) {
    this.accessService = accessService;
    this.folders=folders;
  }

  @PostMapping("/check")
  public AccessCheckResponse checkAccess(
      @Valid @RequestBody AccessCheckRequest request, @AuthenticationPrincipal Jwt jwt) {
    String subject = jwt == null ? null : jwt.getSubject();
    if (subject == null || subject.isBlank() || !subject.equals(request.getUserId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    return accessService.checkAccess(request);
  }

  @GetMapping("/destinations")
  public AccessibleDestinationsResponse getAccessibleDestinations(
      @AuthenticationPrincipal Jwt jwt) {
    String subject = jwt == null ? null : jwt.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    var result=accessService.getAccessibleDestinations(subject);
    return new AccessibleDestinationsResponse(result.destinations().stream().filter(f->!mmiv.user_access_service.repository.FolderRepository.verification(f.destination()) && folders.exists(f.destination())).toList());
  }
}

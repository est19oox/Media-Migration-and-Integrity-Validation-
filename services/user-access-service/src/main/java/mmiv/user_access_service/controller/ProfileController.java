package mmiv.user_access_service.controller;

import java.util.*;
import mmiv.user_access_service.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/access")
public class ProfileController {
  private final UserRepository users; private final FolderRepository folders;
  public ProfileController(UserRepository users,FolderRepository folders) { this.users=users; this.folders=folders; }
  @ModelAttribute void noCache(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
  @GetMapping("/me") public Object me(@AuthenticationPrincipal Jwt jwt) {
    var user = users.findById(jwt.getSubject()).filter(u -> u.isActive()).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    var visible = "ADMIN".equals(user.getRole()) ? folders.list().stream().map(name -> Map.of("destination",name,"permission","WRITE")).toList() : user.getDestinationAccesses().stream().filter(a -> folders.exists(a.getDestination()))
        .map(a -> Map.of("destination",a.getDestination(),"permission",a.getPermission().name())).toList();
    return Map.of("userId",user.getUserId(),"username",user.getUsername(),"role",user.getRole(),"destinations",visible);
  }
  @GetMapping("/folders/{name}") public Object folder(@AuthenticationPrincipal Jwt jwt,@PathVariable String name) {
    if(!folders.exists(name)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Folder not found");
    var user=users.findById(jwt.getSubject()).filter(u->u.isActive()).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN));
    if(!"ADMIN".equals(user.getRole()) && user.getDestinationAccesses().stream().noneMatch(a->name.equals(a.getDestination())))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No access to folder");
    return Map.of("name",name);
  }
}

package mmiv.reporting.api;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import org.bson.Document;

@RestController
public class DashboardController {
  private final MongoTemplate mongo; private final RestClient metadata,access;
  @ModelAttribute void noCache(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
  public DashboardController(MongoTemplate mongo,@Value("${mmiv.metadata.url:http://discovery-service:8085}") String metadataUrl,
      @Value("${mmiv.access.url:http://user-access-service:8083}") String accessUrl) {
    this.mongo=mongo;
    var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());
    factory.setReadTimeout(java.time.Duration.ofSeconds(10));
    metadata=RestClient.builder().baseUrl(metadataUrl).requestFactory(factory).build();access=RestClient.builder().baseUrl(accessUrl).requestFactory(factory).build();
  }
  @GetMapping("/api/dashboard") public Object logs(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String folder,@RequestParam(defaultValue="0") int page) {
    if(page<0 || page>100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page");
    try {
      if(folder==null) {
        access.get().uri("/api/admin/me").header("Authorization","Bearer "+jwt.getTokenValue()).retrieve().toBodilessEntity();
        return Map.of("logs",mongo.find(new Query().with(Sort.by(Sort.Direction.DESC,"occurredAt")).skip(page*100L).limit(100),Document.class,"service_logs"),"page",page);
      }
      var plans=metadata.get().uri(b->b.path("/api/metadata/folders/{folder}/plans").queryParam("page",page).build(folder))
          .header("Authorization","Bearer "+jwt.getTokenValue()).retrieve().body(Plan[].class);
      var ids=Arrays.stream(plans==null?new Plan[0]:plans).map(p->p.migrationId().toString()).toList();
      return Map.of("reports",mongo.find(Query.query(Criteria.where("_id").in(ids)),Document.class,"migration_reports"),
          "events",mongo.find(Query.query(Criteria.where("migrationId").in(ids)).with(Sort.by(Sort.Direction.DESC,"occurredAt")).limit(500),Document.class,"audit_events"),"page",page,"hasMore",ids.size()==100);
    } catch(HttpClientErrorException e) {throw new ResponseStatusException(e.getStatusCode(),"No access to logs");}
    catch(RestClientException e) {throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Authorization service unavailable");}
  }
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
  public record Plan(UUID migrationId) {}
}

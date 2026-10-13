package mmiv.reporting.api;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DashboardPrivacyTest {
 @Test void ordinaryUsersHaveOnlyTheirOwnSanitizedActivityAndNoDetailedDashboard() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/api/access/me",e->{e.sendResponseHeaders(200,-1);e.close();});server.createContext("/api/admin/me",e->{e.sendResponseHeaders(403,-1);e.close();});server.start();
  try{String url="http://127.0.0.1:"+server.getAddress().getPort();var mongo=mock(MongoTemplate.class);var controller=new DashboardController(mongo,url,url);var jwt=Jwt.withTokenValue("fixture").header("alg","RS256").subject("alice-id").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.logs(jwt,"SW001",0));verifyNoInteractions(mongo);
   var payload=new Document("actorId","alice-id").append("operation","DOWNLOAD").append("folder","SW001").append("fileName","report.pdf").append("success",true).append("internalSecret","private");
   when(mongo.find(any(Query.class),eq(Document.class),eq("service_logs"))).thenReturn(List.of(new Document("occurredAt","2026-10-13T00:00:00Z").append("data",payload)));
   var result=(Map<?,?>)controller.history(jwt,0);assertFalse(result.toString().contains("internalSecret"));assertFalse(result.toString().contains("alice-id"));assertTrue(result.toString().contains("report.pdf"));
   verify(mongo).find(argThat((Query q)->q.getQueryObject().get("data.actorId").equals("alice-id")&&q.getLimit()==51),eq(Document.class),eq("service_logs"));
  }finally{server.stop(0);}
 }
}

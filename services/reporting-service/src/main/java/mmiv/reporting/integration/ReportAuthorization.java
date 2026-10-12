package mmiv.reporting.integration;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
@Component
public class ReportAuthorization {
    private final RestClient metadata;
    public ReportAuthorization(@Value("${mmiv.metadata.url:http://discovery-service:8085}") String url){
        var f=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());f.setReadTimeout(Duration.ofSeconds(5));
        metadata=RestClient.builder().baseUrl(url).requestFactory(f).build();
    }
    public void require(UUID id){
        if(!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try {metadata.get().uri("/api/metadata/plans/{id}",id).header("Authorization","Bearer "+token.getToken().getTokenValue()).retrieve().toBodilessEntity();}
        catch(HttpClientErrorException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Report permission required");}
        catch(RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Metadata unavailable");}
    }
}

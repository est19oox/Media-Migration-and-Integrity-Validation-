package mmiv.integration;
import java.util.UUID;
import java.util.Map;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
@Component
public class TransferClient {
    private final RestClient client;
    public TransferClient(@Value("${mmiv.transfer.url:http://transfer-service:8081}") String url) {
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofMinutes(3)); client=RestClient.builder().requestInterceptor(mmiv.resilience.DependencyCircuits.http("transfer",url)).baseUrl(url).requestFactory(factory).build();
    }
    public Object transfer(UUID metadataId,String token) {
        try {return client.post().uri("/api/v1/transfers").headers(headers->{headers.setBearerAuth(token);
            if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() instanceof org.springframework.web.context.request.ServletRequestAttributes attributes){
                String run=attributes.getRequest().getHeader("X-MMIV-Verification-Run");if(run!=null)headers.set("X-MMIV-Verification-Run",UUID.fromString(run).toString());
            }})
                .body(Map.of("metadataId",metadataId)).retrieve().body(Object.class);}
        catch(RestClientException e) {throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Transfer service unavailable");}
    }
}

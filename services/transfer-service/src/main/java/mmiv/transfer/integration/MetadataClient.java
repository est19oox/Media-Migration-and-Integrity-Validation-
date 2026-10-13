package mmiv.transfer.integration;

import java.util.*;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MetadataClient {
    private final RestClient metadata;
    private final RestClient access;
    private final String bucket;
    private final String destination;
    private final String metadataBase;
    public MetadataClient(@Value("${mmiv.metadata.url:http://discovery-service:8085}") String url,
            @Value("${mmiv.access.url:http://user-access-service:8083}") String accessUrl,
            @Value("${mmiv.s3.bucket}") String bucket,
            @Value("${mmiv.metadata.destination-id:${mmiv.s3.bucket}}") String destination) {
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        metadata=RestClient.builder().requestInterceptor(mmiv.resilience.DependencyCircuits.http("metadata",url)).baseUrl(url).requestFactory(factory).build();
        access=RestClient.builder().requestInterceptor(mmiv.resilience.DependencyCircuits.http("user-access",accessUrl)).baseUrl(accessUrl).requestFactory(factory).build();this.bucket=bucket;this.destination=destination;this.metadataBase=url;
    }
    public static Jwt currentJwt() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Login required");
        return token.getToken();
    }
    public Plan resolve(UUID id) {
        if(id==null) throw new IllegalArgumentException("metadataId is required; destination keys are assigned by metadata");
        return request(()->metadata.get().uri("/api/metadata/plans/{id}/resolve",id)
                .header("Authorization","Bearer "+currentJwt().getTokenValue()).retrieve().body(Plan.class));
    }
    public Plan allocate(String name,long size,String destinationId) {return allocate(name,size,destinationId,null);}
    public Plan resolveDelete(UUID id){return request(()->metadata.get().uri("/api/metadata/plans/{id}/delete-authorization",id).header("Authorization","Bearer "+currentJwt().getTokenValue()).retrieve().body(Plan.class));}
    public Set<String> ownedKeys(List<UUID> ids){
        try{var keys=metadata.post().uri("/api/metadata/ownership").header("Authorization","Bearer "+currentJwt().getTokenValue()).body(ids).retrieve().body(String[].class);return keys==null?Set.of():Set.copyOf(Arrays.asList(keys));}
        catch(RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Ownership service unavailable");}
    }
    public void releaseName(UUID id){try{metadata.post().uri("/api/metadata/plans/{id}/release-name",id).header("Authorization","Bearer "+currentJwt().getTokenValue()).retrieve().toBodilessEntity();}catch(RestClientException error){org.slf4j.LoggerFactory.getLogger(MetadataClient.class).warn("Upload name reservation could not be released");}}
    public void deletePlan(UUID id,String key){mutate("/api/metadata/plans/"+id+"?key="+PortalClient.encode(key));}
    public void deleteFolderPlans(String folder,String path){mutate("/api/metadata/folders/"+PortalClient.encode(folder)+"/plans"+(path==null?"":"?path="+PortalClient.encode(path)));}
    private void mutate(String uri){try{metadata.delete().uri(java.net.URI.create(metadataBase+uri)).header("Authorization","Bearer "+currentJwt().getTokenValue()).retrieve().toBodilessEntity();}
        catch(HttpClientErrorException e){throw new ResponseStatusException(e.getStatusCode(),"Metadata change denied");}catch(RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Metadata unavailable");}}
    public boolean verificationClosed(UUID run){try{var result=metadata.get().uri("/api/metadata/verification/{run}/closed",run).header("Authorization","Bearer "+currentJwt().getTokenValue()).retrieve().body(java.util.Map.class);return result!=null&&Boolean.TRUE.equals(result.get("closed"));}catch(RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Verification state unavailable");}}
    public VerificationFolder verificationFolder(UUID run){
        try{return access.get().uri("/api/access/verification/{run}/folder",run).header("Authorization","Bearer "+currentJwt().getTokenValue()).retrieve().body(VerificationFolder.class);}
        catch(RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Verification folder unavailable");}
    }
    public void removeVerificationFolder(UUID run){try{access.post().uri("/api/access/verification/{run}/cleanup",run).header("Authorization","Bearer "+currentJwt().getTokenValue()).retrieve().toBodilessEntity();}
        catch(RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Verification folder cleanup failed");}}
    public record VerificationFolder(boolean owned,String name) {}
    public Plan allocate(String name,long size,String destinationId,String relativePath){return allocate(name,size,destinationId,relativePath,java.util.Set.of());}
    public Plan allocate(String name,long size,String destinationId,String relativePath,java.util.Set<String> occupied) {
        var payload=new java.util.LinkedHashMap<String,Object>();payload.put("fileName",name);payload.put("sizeBytes",size);
        payload.put("occupiedPaths",occupied);payload.put("destinationId",destinationId);if(relativePath!=null)payload.put("relativePath",relativePath);
        return request(()->metadata.post().uri("/api/metadata/allocations").headers(headers->{headers.setBearerAuth(currentJwt().getTokenValue());
            if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) {
                String run=attributes.getRequest().getHeader("X-MMIV-Verification-Run");if(run!=null)headers.set("X-MMIV-Verification-Run",UUID.fromString(run).toString());
            }})
                .body(payload)
                .retrieve().body(Plan.class));
    }
    public List<Plan> verificationPlans(UUID run) {
        try {
            var response=metadata.get().uri("/api/metadata/verification/{run}/plans",run)
                .header("Authorization","Bearer "+currentJwt().getTokenValue()).retrieve().body(Plan[].class);
            if(response==null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid cleanup assignment");
            return Arrays.stream(response).map(plan->request(()->plan)).peek(plan->{
                if(!run.equals(plan.verificationRunId())) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid cleanup run");
            }).toList();
        } catch(HttpClientErrorException error){throw new ResponseStatusException(error.getStatusCode(),"Cleanup assignment rejected");}
        catch(RestClientException error){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Metadata unavailable");}
    }
    public Object destinations() {
        return Map.of("destinations",profile().destinations().stream().filter(f->"WRITE".equals(f.permission())).toList());
    }
    public Profile profile() {
        try {return access.get().uri("/api/access/me").header("Authorization","Bearer "+currentJwt().getTokenValue()).retrieve().body(Profile.class);}
        catch(HttpClientErrorException e){throw new ResponseStatusException(e.getStatusCode(),"Account unavailable");}
        catch(RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Account service unavailable");}
    }
    public record Folder(String destination,String permission) {}
    public record Profile(String userId,String username,String role,List<Folder> destinations) {}
    public void requireAccess(String action) {
        requireAccess(destination,action);
    }
    public void requireFolder(String folder,String action) {
        if(folder==null || !folder.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}") || folder.equals(bucket) || folder.equals("migrations") || folder.equals("upload"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No access to folder");
        try {access.get().uri("/api/access/folders/{name}",folder).header("Authorization","Bearer "+currentJwt().getTokenValue()).retrieve().toBodilessEntity();}
        catch(HttpClientErrorException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No access to folder");}
        catch(RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Folder service unavailable");}
        requireAccess(folder,action);
    }
    public void requireAccess(String folder,String action) {
        var jwt=currentJwt();
        try {
            var decision=access.post().uri("/api/access/check").header("Authorization","Bearer "+jwt.getTokenValue())
                .body(Map.of("userId",jwt.getSubject(),"destination",folder,"action",action)).retrieve().body(Decision.class);
            if(decision==null || !decision.authorised()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Destination permission required");
        } catch(HttpClientErrorException e) {throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Destination permission required");}
        catch(RestClientException e) {throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Permission service unavailable");}
    }
    private Plan request(java.util.function.Supplier<Plan> action) {
        try {
            var p=action.get();
            if(p==null || p.metadataId()==null || !p.metadataId().equals(p.migrationId()) || p.fileId()==null
                    || !currentJwt().getSubject().equals(p.ownerId()) || !bucket.equals(p.bucket()) || p.destinationId()==null
                    || !p.destinationId().matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}")
                    || p.fileName()==null || !p.fileName().matches("[\\p{L}\\p{N}._ ()-]{1,200}")
                    || p.objectPath()==null || p.objectPath().startsWith("/") || p.objectPath().contains("\\")
                    || java.util.Arrays.stream(p.objectPath().split("/",-1)).anyMatch(part->part.isBlank() || part.equals(".") || part.equals(".."))
                    || !p.objectPath().substring(p.objectPath().lastIndexOf('/')+1).equals(p.fileName())
                    || !(p.destinationId()+"/migrations/"+p.metadataId()+"/"+p.objectPath()).equals(p.destinationKey()))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid metadata assignment");
            return p;
        } catch(HttpClientErrorException e) {throw new ResponseStatusException(e.getStatusCode(),"Metadata assignment rejected");}
        catch(RestClientException e) {throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Metadata service unavailable");}
    }
    public record Decision(boolean authorised,String reason) {}
    public record Plan(UUID metadataId,UUID migrationId,UUID fileId,String ownerId,String destinationId,String bucket,
            String destinationKey,String sourcePath,String fileName,String contentType,long sizeBytes,String kind,String objectPath,UUID verificationRunId) {
        public Plan(UUID metadataId,UUID migrationId,UUID fileId,String ownerId,String destinationId,String bucket,
                String destinationKey,String sourcePath,String fileName,String contentType,long sizeBytes,String kind,String objectPath) {
            this(metadataId,migrationId,fileId,ownerId,destinationId,bucket,destinationKey,sourcePath,fileName,contentType,sizeBytes,kind,objectPath,null);
        }
    }
}

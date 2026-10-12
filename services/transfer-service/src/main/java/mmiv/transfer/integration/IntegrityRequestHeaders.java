package mmiv.transfer.integration;
import feign.RequestInterceptor;
import org.springframework.context.annotation.*;
@Configuration
public class IntegrityRequestHeaders {
    @Bean public RequestInterceptor integrityIdentity() {
        return request->{
            request.header("Authorization","Bearer "+MetadataClient.currentJwt().getTokenValue());
            var p=MigrationContext.get(); if(p!=null) {
                request.header("X-MMIV-Migration-Id",p.migrationId().toString());
                request.header("X-MMIV-File-Id",p.fileId().toString());
                request.header("X-MMIV-Metadata-Id",p.metadataId().toString());
            }
        };
    }
}

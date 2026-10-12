package mmiv.controller;
import java.util.UUID;
import mmiv.integration.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class MetadataControllerContractTest {
    @Test void sourcePlanDoesNotRequireClientFileSizeOrDestinationKey() throws Exception {
        var metadata=mock(MetadataService.class);var id=UUID.randomUUID();
        var plan=new MetadataPlan(id,id,UUID.randomUUID(),"owner","landing","bucket","migrations/"+id+"/test.txt","test.txt","test.txt","text/plain",5,"SOURCE","test.txt");
        when(metadata.create(isNull(),any(MetadataService.CreatePlan.class),eq(false))).thenReturn(plan);
        var mvc=MockMvcBuilders.standaloneSetup(new MetadataController(metadata,mock(TransferClient.class)))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(post("/api/metadata/plans").contentType("application/json")
                .content("{\"destinationId\":\"landing\",\"sourcePath\":\"test.txt\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.destinationKey").value(plan.destinationKey()));
        var request=ArgumentCaptor.forClass(MetadataService.CreatePlan.class);
        verify(metadata).create(isNull(),request.capture(),eq(false));
        assertNull(request.getValue().sizeBytes());assertEquals("test.txt",request.getValue().sourcePath());
    }
}

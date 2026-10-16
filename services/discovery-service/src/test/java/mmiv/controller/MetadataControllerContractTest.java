package mmiv.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import mmiv.integration.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MetadataControllerContractTest {
  @Test
  void batchAllocationForwardsFilesAndVerificationRun() throws Exception {
    var metadata = mock(MetadataService.class);
    UUID run = UUID.randomUUID();
    UUID migration = UUID.randomUUID();
    when(metadata.allocateBatch(isNull(), any(BatchAllocationRequest.class), eq(run)))
        .thenReturn(new BatchAllocationResponse(migration, java.util.List.of()));
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new MetadataController(
                    metadata, mock(TransferClient.class), mock(VerificationCleanupService.class)))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

    mvc.perform(
            post("/api/metadata/allocations/batch")
                .header("X-MMIV-Verification-Run", run)
                .contentType("application/json")
                .content(
                    """
                    {"destinationId":"landing","occupiedPaths":[],"files":[
                      {"fileName":"a.txt","sizeBytes":5,"relativePath":"a.txt"}
                    ]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.migrationId").value(migration.toString()));
    verify(metadata).allocateBatch(isNull(), any(BatchAllocationRequest.class), eq(run));
  }

  @Test
  void sourcePlanDoesNotRequireClientFileSizeOrDestinationKey() throws Exception {
    var metadata = mock(MetadataService.class);
    var id = UUID.randomUUID();
    var plan =
        new MetadataPlan(
            id,
            id,
            UUID.randomUUID(),
            "owner",
            "landing",
            "bucket",
            "migrations/" + id + "/test.txt",
            "test.txt",
            "test.txt",
            "text/plain",
            5,
            "SOURCE",
            "test.txt");
    when(metadata.create(isNull(), any(MetadataService.CreatePlan.class), eq(false), isNull()))
        .thenReturn(plan);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new MetadataController(
                    metadata, mock(TransferClient.class), mock(VerificationCleanupService.class)))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
    mvc.perform(
            post("/api/metadata/plans")
                .contentType("application/json")
                .content("{\"destinationId\":\"landing\",\"sourcePath\":\"test.txt\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.destinationKey").value(plan.destinationKey()));
    var request = ArgumentCaptor.forClass(MetadataService.CreatePlan.class);
    verify(metadata).create(isNull(), request.capture(), eq(false), isNull());
    assertNull(request.getValue().sizeBytes());
    assertEquals("test.txt", request.getValue().sourcePath());
  }
}

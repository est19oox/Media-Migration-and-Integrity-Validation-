package mmiv.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import mmiv.integration.*;
import mmiv.model.MetadataPlan;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MetadataControllerContractTest {
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

  @Test
  void batchAllocationEndpointReturnsOneMigrationForAllPlans() throws Exception {
    var metadata = mock(MetadataService.class);
    UUID migration = UUID.randomUUID();
    UUID verificationRun = UUID.randomUUID();
    var first = plan(migration, "folder/a.txt");
    var second = plan(migration, "folder/b.txt");
    var response = new BatchAllocationResponse(migration, java.util.List.of(first, second));
    when(metadata.allocateBatch(isNull(), any(BatchAllocationRequest.class), eq(verificationRun)))
        .thenReturn(response);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new MetadataController(
                    metadata, mock(TransferClient.class), mock(VerificationCleanupService.class)))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

    mvc.perform(
            post("/api/metadata/allocations/batch")
                .header("X-MMIV-Verification-Run", verificationRun)
                .contentType("application/json")
                .content(
                    """
                    {"destinationId":"landing","occupiedPaths":[],"files":[
                      {"fileName":"a.txt","sizeBytes":5,"relativePath":"folder/a.txt"},
                      {"fileName":"b.txt","sizeBytes":5,"relativePath":"folder/b.txt"}
                    ]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.migrationId").value(migration.toString()))
        .andExpect(jsonPath("$.plans.length()").value(2));
    verify(metadata)
        .allocateBatch(isNull(), any(BatchAllocationRequest.class), eq(verificationRun));
  }

  private MetadataPlan plan(UUID migration, String path) {
    UUID metadata = UUID.randomUUID();
    String name = path.substring(path.lastIndexOf('/') + 1);
    return new MetadataPlan(
        metadata,
        migration,
        UUID.randomUUID(),
        "owner",
        "landing",
        "bucket",
        "landing/migrations/" + metadata + "/" + path,
        null,
        name,
        "text/plain",
        5,
        "UPLOAD",
        path);
  }
}

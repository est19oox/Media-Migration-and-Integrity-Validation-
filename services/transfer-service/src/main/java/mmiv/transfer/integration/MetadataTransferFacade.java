package mmiv.transfer.integration;

import java.util.*;
import mmiv.events.EventPublisher;
import mmiv.transfer.dto.*;
import mmiv.transfer.service.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MetadataTransferFacade {
  private final ExistingFileNames names;
  private final VerificationRunGuard runs;
  private final FolderAccessGuard accessGuard;
  private final MetadataClient metadata;
  private final TransferService transfers;
  private final FolderUploadService folders;

  @org.springframework.beans.factory.annotation.Value("${mmiv.upload.max-size-bytes:104857600}")
  private long maxUploadBytes = 104857600;

  private final mmiv.transfer.validation.TransferRequestValidator validator;

  public MetadataTransferFacade(
      MetadataClient metadata,
      TransferService transfers,
      FolderUploadService folders,
      EventPublisher events,
      mmiv.transfer.validation.TransferRequestValidator validator,
      VerificationRunGuard runs,
      FolderAccessGuard accessGuard,
      ExistingFileNames names) {
    this.names = names;
    this.accessGuard = accessGuard;
    this.runs = runs;
    this.metadata = metadata;
    this.transfers = transfers;
    this.folders = folders;
    this.validator = validator;
  }

  public TransferResponse transfer(UUID metadataId) {
    var initial = metadata.resolve(metadataId);
    try (@SuppressWarnings("unused")
        var folderScope = accessGuard.read(initial.destinationId())) {
      var plan = metadata.resolve(metadataId);
      if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
          instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) {
        String run = attributes.getRequest().getHeader("X-MMIV-Verification-Run");
        if (run != null && !UUID.fromString(run).equals(plan.verificationRunId()))
          throw new IllegalArgumentException(
              "Verification transfer must use a plan from the same run");
      }
      if (!"SOURCE".equals(plan.kind()) || plan.sourcePath() == null)
        throw new IllegalArgumentException("Metadata requires an uploaded file");
      MigrationContext.set(plan);
      try (var scope = runs.acquire(plan)) {
        return transfers.transfer(
            new TransferRequest(plan.sourcePath(), plan.destinationKey(), plan.contentType()));
      } finally {
        MigrationContext.clear();
      }
    }
  }

  public UploadResponse upload(MultipartFile file, String destinationId) {
    if (file == null) throw new IllegalArgumentException("file is required");
    String name = validator.validateUpload(file, maxUploadBytes);
    try (@SuppressWarnings("unused")
        var folderScope = accessGuard.read(destinationId)) {
      var plan =
          metadata.allocate(name, file.getSize(), destinationId, null, names.paths(destinationId));
      MigrationContext.set(plan);
      try (var scope = runs.acquire(plan)) {
        var result = transfers.uploadAssigned(file, plan.fileName(), plan.destinationKey());
        if (!result.success() && "DELETED".equals(result.cleanupStatus()))
          metadata.releaseName(plan.metadataId());
        return result;
      } finally {
        MigrationContext.clear();
      }
    }
  }

  public FolderUploadResponse folder(
      List<MultipartFile> files, List<String> relativePaths, String destinationId) {
    // Validate the whole folder and authorize every allocation before any object is written.
    folders.validate(files, relativePaths);
    try (@SuppressWarnings("unused")
        var folderScope = accessGuard.read(destinationId)) {
      var occupied = names.paths(destinationId);
      var plans = new java.util.ArrayList<MetadataClient.Plan>();
      for (int i = 0; i < files.size(); i++)
        plans.add(
            metadata.allocate(
                files.get(i).getOriginalFilename(),
                files.get(i).getSize(),
                destinationId,
                relativePaths.get(i),
                occupied));
      var results = new ArrayList<FolderUploadResponse.FileResult>();
      int succeeded = 0;
      for (int i = 0; i < files.size(); i++) {
        MigrationContext.set(plans.get(i));
        try (var scope = runs.acquire(plans.get(i))) {
          var response =
              transfers.uploadAssigned(
                  files.get(i), plans.get(i).fileName(), plans.get(i).destinationKey());
          if (!response.success() && "DELETED".equals(response.cleanupStatus()))
            metadata.releaseName(plans.get(i).metadataId());
          results.add(
              new FolderUploadResponse.FileResult(
                  plans.get(i).objectPath(),
                  response.validationResult(),
                  response,
                  response.success() ? null : response.message()));
          if (response.success()) succeeded++;
        } catch (IllegalStateException | IllegalArgumentException e) {
          results.add(
              new FolderUploadResponse.FileResult(
                  relativePaths.get(i), "FAILED", null, "File transfer failed"));
        } finally {
          MigrationContext.clear();
        }
      }
      return new FolderUploadResponse(
          destinationId + "/", succeeded, files.size() - succeeded, List.copyOf(results));
    }
  }
}

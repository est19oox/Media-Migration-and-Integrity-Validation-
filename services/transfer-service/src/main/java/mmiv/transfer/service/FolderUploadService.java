package mmiv.transfer.service;

import java.util.*;
import mmiv.transfer.domain.FolderUploadManifest;
import mmiv.transfer.dto.FolderUploadResponse;
import mmiv.transfer.dto.FolderUploadResponse.FileResult;
import mmiv.transfer.validation.TransferRequestValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FolderUploadService {
  private final TransferService transfers;
  private final TransferRequestValidator validator;

  @Value("${mmiv.upload.max-size-bytes:104857600}")
  private long maxFileBytes = 104857600;

  @Value("${mmiv.upload.folder.max-total-bytes:104857600}")
  private long maxTotalBytes = 104857600;

  @Value("${mmiv.upload.folder.max-files:500}")
  private int maxFiles = 500;

  public FolderUploadService(TransferService transfers, TransferRequestValidator validator) {
    this.transfers = transfers;
    this.validator = validator;
  }

  public FolderUploadResponse upload(List<MultipartFile> files, List<String> relativePaths) {
    FolderUploadManifest manifest = validatedManifest(files, relativePaths);
    String prefix = "uploads/" + UUID.randomUUID() + "/";
    List<FileResult> results = new ArrayList<>();
    int succeeded = 0;
    for (int i = 0; i < files.size(); i++) {
      var entry = manifest.entries().get(i);
      String path = entry.relativePath();
      try {
        var uploaded = transfers.uploadToKey(files.get(i), entry.fileName(), prefix + path);
        results.add(
            new FileResult(
                path,
                uploaded.validationResult(),
                uploaded,
                uploaded.success() ? null : uploaded.message()));
        if (uploaded.success()) succeeded++;
      } catch (IllegalStateException | IllegalArgumentException e) {
        results.add(new FileResult(path, "FAILED", null, "File transfer failed"));
      }
    }
    return new FolderUploadResponse(
        prefix, succeeded, files.size() - succeeded, List.copyOf(results));
  }

  public void validate(List<MultipartFile> files, List<String> relativePaths) {
    validatedManifest(files, relativePaths);
  }

  private FolderUploadManifest validatedManifest(
      List<MultipartFile> files, List<String> relativePaths) {
    if (files == null
        || relativePaths == null
        || files.isEmpty()
        || files.size() != relativePaths.size()
        || files.size() > maxFiles) {
      throw new IllegalArgumentException(
          "Provide matching files and relativePaths within the folder file limit");
    }
    List<FolderUploadManifest.Entry> entries = new ArrayList<>();
    for (int i = 0; i < files.size(); i++) {
      String name = validator.validateUpload(files.get(i), maxFileBytes);
      entries.add(
          new FolderUploadManifest.Entry(relativePaths.get(i), name, files.get(i).getSize()));
    }
    // Validate the entire folder aggregate before the first S3 write.
    return new FolderUploadManifest(
        entries, maxFiles, maxTotalBytes, "uploads/" + UUID.randomUUID() + "/");
  }
}

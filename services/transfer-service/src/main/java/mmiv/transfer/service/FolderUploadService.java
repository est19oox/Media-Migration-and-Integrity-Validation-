package mmiv.transfer.service;

import java.nio.charset.StandardCharsets;
import java.util.*;
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
    validate(files, relativePaths);
    String prefix = "uploads/" + UUID.randomUUID() + "/";
    List<String> names =
        files.stream().map(f -> validator.validateUpload(f, maxFileBytes)).toList();
    List<FileResult> results = new ArrayList<>();
    int succeeded = 0;
    for (int i = 0; i < files.size(); i++) {
      String path = relativePaths.get(i);
      try {
        var uploaded = transfers.uploadToKey(files.get(i), names.get(i), prefix + path);
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
    if (files == null
        || relativePaths == null
        || files.isEmpty()
        || files.size() != relativePaths.size()
        || files.size() > maxFiles) {
      throw new IllegalArgumentException(
          "Provide matching files and relativePaths within the folder file limit");
    }
    String prefix = "uploads/" + UUID.randomUUID() + "/";
    Set<String> seen = new HashSet<>();
    List<String> names = new ArrayList<>();
    String root = null;
    long total = 0;
    // Validate the entire manifest before the first S3 write.
    for (int i = 0; i < files.size(); i++) {
      String path = relativePaths.get(i);
      validatePath(path, prefix);
      if (!seen.add(path)) throw new IllegalArgumentException("Duplicate relative path");
      String thisRoot = path.substring(0, path.indexOf('/'));
      if (root == null) root = thisRoot;
      if (!root.equals(thisRoot))
        throw new IllegalArgumentException("Select one root folder per request");
      String name = validator.validateUpload(files.get(i), maxFileBytes);
      if (!name.equals(path.substring(path.lastIndexOf('/') + 1))) {
        throw new IllegalArgumentException("Relative path must match the uploaded file name");
      }
      if (files.get(i).getSize() > maxTotalBytes - total) {
        throw new IllegalArgumentException("Folder exceeds the total upload size limit");
      }
      total += files.get(i).getSize();
      names.add(name);
    }
  }

  private static void validatePath(String path, String prefix) {
    if (path == null
        || path.indexOf('/') <= 0
        || path.contains("\\")
        || (prefix + path).getBytes(StandardCharsets.UTF_8).length > 1024) {
      throw new IllegalArgumentException("Invalid folder relative path");
    }
    String[] parts = path.split("/", -1);
    if (parts.length > 64)
      throw new IllegalArgumentException("Folder nesting exceeds 64 path segments");
    for (String part : parts) {
      if (part.isBlank()
          || part.equals(".")
          || part.equals("..")
          || part.length() > 200
          || !part.matches("[\\p{L}\\p{N}._ ()-]+")) {
        throw new IllegalArgumentException("Invalid folder path segment");
      }
    }
  }
}

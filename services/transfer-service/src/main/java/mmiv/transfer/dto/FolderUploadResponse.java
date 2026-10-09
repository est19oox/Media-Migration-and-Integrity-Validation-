package mmiv.transfer.dto;

import java.util.List;

/** A folder upload is not atomic; inspect every file result before retrying. */
public record FolderUploadResponse(String prefix, int succeeded, int failed, List<FileResult> files) {
    public record FileResult(String relativePath, String status, UploadResponse upload, String error) {}
}

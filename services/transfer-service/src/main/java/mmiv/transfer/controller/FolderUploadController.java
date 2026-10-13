package mmiv.transfer.controller;

import java.util.List;
import mmiv.transfer.dto.FolderUploadResponse;
import mmiv.transfer.integration.MetadataTransferFacade;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/transfers/folder")
public class FolderUploadController {
  private final MetadataTransferFacade transfers;

  public FolderUploadController(MetadataTransferFacade transfers) {
    this.transfers = transfers;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public FolderUploadResponse upload(
      @RequestParam(value = "files", required = false) List<MultipartFile> files,
      @RequestParam(value = "relativePaths", required = false) List<String> relativePaths,
      @RequestParam(value = "destinationId", required = false) String destinationId) {
    return transfers.folder(files, relativePaths, destinationId);
  }
}

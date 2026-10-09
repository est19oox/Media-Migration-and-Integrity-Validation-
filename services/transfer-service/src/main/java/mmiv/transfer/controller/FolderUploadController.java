package mmiv.transfer.controller;

import mmiv.transfer.dto.FolderUploadResponse;
import mmiv.transfer.service.FolderUploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/v1/transfers/folder")
public class FolderUploadController {
    private final FolderUploadService folders;
    public FolderUploadController(FolderUploadService folders) { this.folders = folders; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FolderUploadResponse upload(@RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "relativePaths", required = false) List<String> relativePaths) {
        return folders.upload(files, relativePaths);
    }
}

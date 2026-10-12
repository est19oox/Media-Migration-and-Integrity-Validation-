package mmiv.transfer.controller;
import java.util.UUID;
import mmiv.transfer.dto.*;
import mmiv.transfer.integration.MetadataTransferFacade;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {
    private final MetadataTransferFacade transfers;
    public TransferController(MetadataTransferFacade transfers){this.transfers=transfers;}
    public record Request(UUID metadataId) {}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransferResponse> transfer(@RequestBody Request request){return ResponseEntity.ok(transfers.transfer(request.metadataId()));}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file,
            @RequestParam(value="destinationId",required=false) String destinationId){return ResponseEntity.ok(transfers.upload(file,destinationId));}
}

// RESERVED: fully commented out; not invoked.
// package mmiv.transfer.integration;
// 
// import mmiv.transfer.dto.UploadResponse;
// import org.springframework.stereotype.Service;
// import java.util.UUID;
// 
// @Service
// public class IntegrityValidationGateway {
//     private final IntegrityClient client;
//     public IntegrityValidationGateway(IntegrityClient client) { this.client = client; }
// 
//     public IntegrityValidationResponse validate(UploadResponse upload) {
//         // Persist this ID with a validation job and reuse it for retries.
//         String requestId = UUID.randomUUID().toString();
//         return client.validate(new IntegrityValidationRequest(requestId,
//                 upload.bucket(), upload.key(), upload.sha256(), upload.sizeBytes()));
//     }
// }

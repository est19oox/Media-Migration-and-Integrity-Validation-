// RESERVED: fully commented out; no runtime dependency or API call.
// package mmiv.transfer.integration;
// 
// import org.springframework.cloud.openfeign.FeignClient;
// import org.springframework.web.bind.annotation.PostMapping;
// import org.springframework.web.bind.annotation.RequestBody;
// 
// // No fixed URL: resolve integrity-service through Eureka and LoadBalancer.
// // This path is a proposed API contract, not an existing verified endpoint.
// @FeignClient(name = "integrity-service")
// public interface IntegrityClient {
//     @PostMapping(value = "/api/v1/validations", consumes = "application/json")
//     IntegrityValidationResponse validate(@RequestBody IntegrityValidationRequest request);
// }

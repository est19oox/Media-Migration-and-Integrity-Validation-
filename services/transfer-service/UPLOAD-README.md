# Local file upload, viewing and download

POST /api/v1/transfers accepts multipart/form-data with one part named file. Users send actual local bytes; no EC2 sourcePath is required. Existing JSON path migration remains available with Content-Type application/json.

Start the deployed service and open http://EC2_PUBLIC_IP:8081/upload.html for the same-origin upload form. A temporary inbound rule from your IP is needed for direct public access. On EC2 the same page is served at port 8081. For a local Spring Boot run, open http://localhost:8081/upload.html.

Postman: POST /api/v1/transfers -> Body -> form-data -> file (type File) -> choose your file. Do not set Content-Type manually. Example from your own terminal:

    curl -i -F "file=@/path/on/your/computer/photo.png" http://EC2_PUBLIC_IP:8081/api/v1/transfers

Supported extensions: PNG, JPG, JPEG, MP4, TXT, PDF. Nonempty files up to 100 MiB are accepted. Original directory components are discarded; invalid names are rejected. The server generates uploads/<UUID>/<fileName> keys, stages to a private temporary file, computes SHA-256, sends S3 a checksum, and deletes the temporary file in finally. It avoids loading the full upload into Java heap. Spring may also use temporary disk for multipart parsing. Provision disk space for concurrent uploads.

Extension checks and canonical MIME labels do not verify actual file contents; no malware scan or independent Integrity result is implemented. UploadResponse reports storage success and the server-computed hash, not a validation PASS. The existing service has no JWT authorization enforcement; this version does not claim to add login/access-control integration. Use the existing test environment until that integration is implemented. No Metadata or presigned URL integration is included.

Integrity integration is fully commented out: integration/IntegrityClient.java, IntegrityValidationRequest.java, IntegrityValidationResponse.java, IntegrityFeignConfiguration.java and IntegrityValidationGateway.java. Feign calls the proposed POST /api/v1/validations using the logical name integrity-service, resolved by Eureka. Confirm the real path, field names, authentication and response with the Integrity developer before enabling.

Enable later:
1. Uncomment the Spring Cloud property, BOM and the three starters in pom.xml.
2. Uncomment the integration Java files (remove the leading // on each code line).
3. Uncomment spring.cloud.openfeign and eureka configuration in application.yml; set EUREKA_DEFAULT_ZONE to a registry reachable from the deployed container. The registry must actually run and Integrity must register as integrity-service. Registry client code does not create a registry server.
4. Inject IntegrityValidationGateway into TransferService and call validate(response) after S3 upload. The gateway includes the full Feign call. Agree on API authentication, durable validation status and retries first; a timeout must not trigger a duplicate upload or be reported as successful validation. requestId must be persisted/reused across retries.

Build using the project's existing Java 25 and Maven: mvn -B clean verify in services/transfer-service. Dependencies, Java version and Docker base are preserved from the uploaded project. Java 25 and Maven were located during verification, but the Spring Boot 3.5.14 parent was unavailable in the local cache and dependency downloads were blocked; full Maven/JUnit execution could not complete.

## View or download uploaded files

The upload response now includes `fileId`. Keep this value to reopen the file. The service streams the original S3 bytes; it does not convert media or generate a preview copy. The upload page includes preview and download links. Open `/files.html` to enter a previously returned fileId and view supported images, MP4, TXT or PDF (PDF rendering depends on the browser).

| Method | Route | Result |
|---|---|---|
| POST | `/api/v1/transfers` | Multipart local upload; JSON legacy migration also remains |
| GET | `/api/v1/files/{fileId}/content` | Inline supported content; unknown types download |
| GET | `/api/v1/files/{fileId}/download` | Attachment containing original bytes |
| HEAD | Either read route | Content type, size and ETag, without fetching the body |

Example after uploading (replace FILE_ID with the response value):

```bash
curl -o downloaded.png http://EC2_PUBLIC_IP:8081/api/v1/files/FILE_ID/download
curl -i -H 'Range: bytes=0-1023' http://EC2_PUBLIC_IP:8081/api/v1/files/FILE_ID/content
```

Single byte ranges support MP4 seeking: closed, open-ended and suffix forms return 206. Unsupported, multiple or unsatisfiable ranges return 416 and `Content-Range: bytes */SIZE`. HEAD ignores Range. If-Range accepts the exact ETag; other values return the full file. Reads use the configured bucket and `If-Match` to prevent a HEAD/GET version race. S3 failures map to 403/404/412/502; no temporary download file is created.

`fileId` is URL-safe Base64 of the S3 key. It is reversible and does not expire; it is NOT an access credential. There is no ownership enforcement or file-list database in this version. Anyone able to reach this unauthenticated test service and construct an ID can request objects in its configured bucket. Add User Access authorization and ownership checks before shared or public use. Keep this deployment restricted to the test environment. Existing legacy transfer responses also include fileId, so migrated objects can be downloaded.

The existing Terraform policy already grants GetObject; no IAM or Terraform change is needed. Do not make the bucket public. Integrity/Feign/Eureka code remains fully commented out. Metadata and presigned URLs are not used.

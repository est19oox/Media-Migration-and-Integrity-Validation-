Open the Gateway root on port 8080 to log in. Login establishes an HttpOnly cookie and redirects to /upload.html. Anonymous Transfer pages redirect to login; APIs return 401. API clients must provide a Bearer token. The original local direct-page examples below should be used through the Gateway for browser login.

# Local file upload, viewing and download

POST /api/v1/transfers accepts multipart/form-data with one part named file. Users send actual local bytes; no EC2 sourcePath is required. Existing JSON path migration remains available with Content-Type application/json.

For deployed browser access, run bash scripts/cd/open-gateway-tunnel.sh <instance-id> and open http://localhost:8080/upload.html while the tunnel is running. Requests go through API Gateway. See docs/ci-cd/gateway-access.md for PowerShell instructions. Deployed host ports bind to loopback and EC2 has no public inbound rules. For a local Spring Boot run, open http://localhost:8081/upload.html.

Postman: POST /api/v1/transfers -> Body -> form-data -> file (type File) -> choose your file. Do not set Content-Type manually. Example from your own terminal:

    curl -i -H "Authorization: Bearer $TOKEN" -F "file=@/path/on/your/computer/photo.png" http://EC2_PUBLIC_IP:8080/api/v1/transfers

Supported extensions: PNG, JPG, JPEG, MP4, TXT, PDF. Nonempty files up to 100 MiB are accepted. Original directory components are discarded; invalid names are rejected. The server generates uploads/<UUID>/<fileName> keys, stages to a private temporary file, computes SHA-256, sends S3 a checksum, and deletes the temporary file in finally. It avoids loading the full upload into Java heap. Spring may also use temporary disk for multipart parsing. Provision disk space for concurrent uploads.

After S3 upload, Transfer calls Integrity through Feign and Eureka and returns
`transferId`, `validationResult`, `success`, and `message`. Only PASSED means a
successful verified transfer. FAILED means a checksum mismatch; UNAVAILABLE means
validation could not be confirmed. After up to three validation attempts, unsuccessful uploads are automatically
deleted. `cleanupStatus` distinguishes DELETED from DELETE_FAILED. The browser
displays this result and hides links for unsuccessful transfers. See
[INTEGRITY-FEIGN.md](INTEGRITY-FEIGN.md) for the contract, configuration, and limits.
Extension checks and MIME labels do not verify file format or scan for malware.
Transfer now requires a valid User Access JWT for pages, uploads and file reads. See ../../docs/ci-cd/login-access.md.

Build using the project's existing Java 25 and Maven: mvn -B clean verify in services/transfer-service. The service uses Spring Boot 4.1.1, Spring Framework 7.0.9 and Spring Cloud 2025.1.3. The production Java version remains 25.

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
curl -H "Authorization: Bearer $TOKEN" -o downloaded.png http://EC2_PUBLIC_IP:8081/api/v1/files/FILE_ID/download
curl -i -H "Authorization: Bearer $TOKEN" -H 'Range: bytes=0-1023' http://EC2_PUBLIC_IP:8081/api/v1/files/FILE_ID/content
```

Single byte ranges support MP4 seeking: closed, open-ended and suffix forms return 206. Unsupported, multiple or unsatisfiable ranges return 416 and `Content-Range: bytes */SIZE`. HEAD ignores Range. If-Range accepts the exact ETag; other values return the full file. Reads use the configured bucket and `If-Match` to prevent a HEAD/GET version race. S3 failures map to 403/404/412/502; no temporary download file is created.

`fileId` is URL-safe Base64 of the S3 key. It identifies an object and is not an access credential. All file reads now require login, but this version does not track per-user ownership: any authenticated user with an object ID can read it. See ../../docs/ci-cd/login-access.md for login and session behavior.

The existing Terraform policy already grants GetObject; no IAM or Terraform change is needed. Do not make the bucket public. Integrity calls use Feign and Eureka service discovery. Metadata and presigned URLs are not used.

Office formats PPT, PPTX, DOC and DOCX are supported for single and folder uploads and server-side transfers. Office files download for viewing. Folder uploads use POST /api/v1/transfers/folder with files and relativePaths; the 500-file and 100 MiB total limits remain in effect.

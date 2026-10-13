package mmiv.transfer.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import mmiv.transfer.files.*;
import mmiv.transfer.logging.FileEventLogger;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/api/v1/files")
public class FileController {
  private final FileReadService files;
  private final FileEventLogger logs;

  public FileController(FileReadService files, FileEventLogger logs) {
    this.files = files;
    this.logs = logs;
  }

  @RequestMapping(
      value = {"/{fileId}/content", "/{fileId}/download"},
      method = {RequestMethod.GET, RequestMethod.HEAD})
  public void read(
      @PathVariable String fileId,
      @RequestHeader(value = "Range", required = false) String rangeHeader,
      @RequestHeader(value = "If-Range", required = false) String ifRange,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Instant startedAt = Instant.now();
    StoredFile file = null;
    long bytesServed = 0;
    boolean success = false;
    String errorType = null;
    boolean head = request.getMethod().equals("HEAD");
    String operation = request.getRequestURI().endsWith("/download") ? "DOWNLOAD" : "PREVIEW";
    try {
      file = files.describe(fileId);
      // An unmatched If-Range (including an unsupported date form) returns the full file.
      String selectedRange = ifRange != null && !ifRange.equals(file.eTag()) ? null : rangeHeader;
      ByteRange range = head ? null : ByteRange.parse(selectedRange, file.sizeBytes());
      boolean download = request.getRequestURI().endsWith("/download");
      if (head) {
        headers(response, file, null, download);
        return;
      }
      // Open S3 before committing headers so storage failures return proper error responses.
      try (var input = files.open(file, range)) {
        headers(response, file, range, download);
        try {
          // Count successful writes, including progress before a disconnect.
          var output = response.getOutputStream();
          byte[] buffer = new byte[8192];
          int count;
          while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            bytesServed += count;
          }
          if (bytesServed != (range == null ? file.sizeBytes() : range.length())) {
            throw new IOException("File stream ended before expected bytes were served");
          }
          output.flush();
          success = true;
        } catch (IOException e) {
          input.abort();
          throw e;
        }
      }
    } catch (IOException | RuntimeException error) {
      success = false;
      errorType = error.getClass().getSimpleName();
      throw error;
    } finally {
      // HEAD requests inspect metadata; they do not download file bytes.
      if (!head) logs.download(operation, fileId, file, bytesServed, success, errorType, startedAt);
    }
  }

  private void headers(
      HttpServletResponse response, StoredFile file, ByteRange range, boolean download) {
    String type = previewType(file.fileName());
    boolean inline =
        !download
            && (type.startsWith("image/")
                || type.startsWith("video/")
                || type.startsWith("audio/")
                || type.startsWith("text/plain")
                || type.equals("application/pdf"));
    response.setStatus(range == null ? 200 : 206);
    response.setContentType(type);
    response.setContentLengthLong(range == null ? file.sizeBytes() : range.length());
    response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
    if (range != null)
      response.setHeader(HttpHeaders.CONTENT_RANGE, range.contentRange(file.sizeBytes()));
    if (file.eTag() != null) response.setHeader(HttpHeaders.ETAG, file.eTag());
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION,
        (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(file.fileName(), StandardCharsets.UTF_8)
            .build()
            .toString());
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader(
        "Content-Security-Policy",
        "sandbox; default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'self'");
  }

  private String previewType(String name) {
    int dot = name.lastIndexOf('.');
    String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    return switch (extension) {
      case "png" -> "image/png";
      case "jpg", "jpeg" -> "image/jpeg";
      case "mp4" -> "video/mp4";
      case "mp3" -> "audio/mpeg";
      case "pdf" -> "application/pdf";
      case "ppt" -> "application/vnd.ms-powerpoint";
      case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
      case "doc" -> "application/msword";
      case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      case "txt" -> "text/plain; charset=UTF-8";
      default -> "application/octet-stream";
    };
  }
}

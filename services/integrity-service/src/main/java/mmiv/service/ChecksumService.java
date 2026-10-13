package mmiv.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ChecksumService {

  public String calculateSHA256(MultipartFile file) throws IOException {

    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");

      try (InputStream inputStream = file.getInputStream()) {

        byte[] buffer = new byte[8192];
        int bytesRead;

        while ((bytesRead = inputStream.read(buffer)) != -1) {
          digest.update(buffer, 0, bytesRead);
        }
      }

      return HexFormat.of().formatHex(digest.digest());

    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm is not available", e);
    }
  }
}

package mmiv.transfer.config;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicAppAssetsTest {
  @Test
  void publicAppMetadataDoesNotDependOnAnExpiredBrowserSession() {
    var resolver = new TransferSecurityConfig().transferTokenResolver();
    for (String path : new String[] {"/manifest.webmanifest", "/sw.js", "/icon-192.png"}) {
      var request = new MockHttpServletRequest("GET", path);
      request.setCookies(new Cookie("MMIV_SESSION", "expired"));
      assertNull(resolver.resolve(request));
    }
    var privateRequest = new MockHttpServletRequest("GET", "/files.html");
    privateRequest.setCookies(new Cookie("MMIV_SESSION", "expired"));
    assertEquals("expired", resolver.resolve(privateRequest));
  }
}

package mmiv.gateway.security;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GatewayMetricsAccessTest {
  @Test
  void allowsDockerPeerButRejectsPublicPeerEvenWithSpoofedForwardedHeader() {
    var matcher =
        GatewaySecurityConfiguration.metricsScrapeNetworks("127.0.0.1/32,::1/128,172.16.0.0/12");
    for (String address : new String[] {"127.0.0.1", "172.18.0.5", "203.0.113.9"}) {
      var exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.get("/actuator/prometheus")
                  .remoteAddress(new InetSocketAddress(address, 12345))
                  .header("X-Forwarded-For", "127.0.0.1")
                  .header("Forwarded", "for=127.0.0.1")
                  .build());
      assertEquals(!address.equals("203.0.113.9"), matcher.matches(exchange).block().isMatch());
    }
  }
}

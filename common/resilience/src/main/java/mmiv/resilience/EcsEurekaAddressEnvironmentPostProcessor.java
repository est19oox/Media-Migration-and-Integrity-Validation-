package mmiv.resilience;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Use the task's VPC address for Eureka instead of Fargate's link-local metadata address. */
public final class EcsEurekaAddressEnvironmentPostProcessor implements EnvironmentPostProcessor {
  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    if (environment.getProperty("ECS_CONTAINER_METADATA_URI_V4") == null) return;
    String address;
    try {
      var candidates = new ArrayList<InterfaceAddress>();
      var interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        var network = interfaces.nextElement();
        if (!network.isUp() || network.isLoopback()) continue;
        var addresses = network.getInetAddresses();
        while (addresses.hasMoreElements()) {
          var ip = addresses.nextElement();
          if (ip instanceof Inet4Address) candidates.add(new InterfaceAddress(network.getName(), ip));
        }
      }
      address = selectVpcAddress(candidates);
    } catch (SocketException exception) {
      throw new IllegalStateException("Cannot inspect ECS task network interfaces", exception);
    }
    environment.getPropertySources().addFirst(new MapPropertySource("ecsTaskVpcAddress", Map.of(
        "eureka.instance.ip-address", address,
        "spring.cloud.client.ip-address", address)));
  }

  static String selectVpcAddress(List<InterfaceAddress> candidates) {
    return candidates.stream()
        .filter(candidate -> candidate.address() instanceof Inet4Address)
        .filter(candidate -> candidate.address().isSiteLocalAddress())
        .filter(candidate -> !candidate.address().isLinkLocalAddress())
        .sorted(Comparator.comparing((InterfaceAddress candidate) -> !"eth0".equals(candidate.name()))
            .thenComparing(InterfaceAddress::name))
        .map(candidate -> candidate.address().getHostAddress())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No private IPv4 address found for ECS task Eureka registration"));
  }

  record InterfaceAddress(String name, InetAddress address) {}
}

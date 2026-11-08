package mmiv.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.io.support.SpringFactoriesLoader;

class EcsEurekaAddressEnvironmentPostProcessorTest {
  @Test
  void springBootDiscoversAddressCorrection() {
    var processors = SpringFactoriesLoader.loadFactories(EnvironmentPostProcessor.class, getClass().getClassLoader());
    org.junit.jupiter.api.Assertions.assertTrue(processors.stream().anyMatch(
        EcsEurekaAddressEnvironmentPostProcessor.class::isInstance));
  }

  @Test
  void choosesTaskVpcAddressInsteadOfLinkLocalMetadataAddress() throws Exception {
    assertEquals("10.0.1.25", EcsEurekaAddressEnvironmentPostProcessor.selectVpcAddress(List.of(
        new EcsEurekaAddressEnvironmentPostProcessor.InterfaceAddress("eth1", InetAddress.getByName("169.254.172.2")),
        new EcsEurekaAddressEnvironmentPostProcessor.InterfaceAddress("eth0", InetAddress.getByName("10.0.1.25")))));
  }

  @Test
  void failsRatherThanAdvertisingUnreachableAddress() throws Exception {
    assertThrows(IllegalStateException.class, () -> EcsEurekaAddressEnvironmentPostProcessor.selectVpcAddress(List.of(
        new EcsEurekaAddressEnvironmentPostProcessor.InterfaceAddress("eth0", InetAddress.getByName("169.254.172.2")))));
  }
}

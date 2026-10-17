package mmiv.transfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.context.annotation.Import(mmiv.resilience.EurekaHttpClients.class)
public class TransferServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(TransferServiceApplication.class, args);
  }
}

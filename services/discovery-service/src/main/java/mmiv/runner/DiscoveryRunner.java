package mmiv.runner;

import mmiv.service.DiscoveryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class DiscoveryRunner implements CommandLineRunner {

    private final DiscoveryService discoveryService;
    private final String discoveryRoot;

    public DiscoveryRunner(
            DiscoveryService discoveryService,
            @Value("${discovery.root}") String discoveryRoot) {

        this.discoveryService = discoveryService;
        this.discoveryRoot = discoveryRoot;
    }

    @Override
    public void run(String... args) throws Exception {

        Path semesterPath = Path.of(discoveryRoot);

        int insertedCount =
                discoveryService.discoverAndSave(semesterPath);

        System.out.println("=================================");
        System.out.println("Discovery completed");
        System.out.println("Discovery root: " + semesterPath);
        System.out.println("New files inserted: " + insertedCount);
        System.out.println("=================================");
    }
}
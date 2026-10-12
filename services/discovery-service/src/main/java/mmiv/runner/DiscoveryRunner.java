package mmiv.runner;

import mmiv.model.DiscoveryResult;
import mmiv.model.DiscoveryRunResult;
import mmiv.model.DiscoveryStatus;
import mmiv.model.DiscoverySummary;
import mmiv.service.DiscoveryService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.file.Path;
import java.util.List;

@Component
@ConditionalOnProperty(
        prefix = "discovery",
        name = "run-on-startup",
        havingValue = "true",
        matchIfMissing = false
)
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

        Path semesterPath =
                Path.of(discoveryRoot);

        DiscoveryRunResult result =
                discoveryService
                        .discoverAndSaveWithResult(
                                semesterPath
                        );

        DiscoverySummary summary =
                result.getInventory()
                        .getSummary();

        List<DiscoveryResult> files =
                result.getInventory()
                        .getFiles();

        System.out.println(
                "================================="
        );

        System.out.println(
                "Discovery completed"
        );

        System.out.println(
                "Discovery root: "
                        + semesterPath
        );

        System.out.println();

        System.out.println(
                "Discovery summary"
        );

        System.out.println(
                "Discovered: "
                        + summary.getDiscovered()
        );

        System.out.println(
                "Accepted:   "
                        + summary.getAccepted()
        );

        System.out.println(
                "Skipped:    "
                        + summary.getSkipped()
        );

        System.out.println(
                "Failed:     "
                        + summary.getFailed()
        );

        printSkippedFiles(files);
        printFailedFiles(files);

        System.out.println();

        System.out.println(
                "New files inserted: "
                        + result.getInsertedCount()
        );

        System.out.println(
                "================================="
        );
    }

    private void printSkippedFiles(
            List<DiscoveryResult> files) {

        List<DiscoveryResult> skippedFiles =
                files.stream()
                        .filter(result ->
                                result.getStatus()
                                        == DiscoveryStatus.SKIPPED)
                        .toList();

        if (skippedFiles.isEmpty()) {
            return;
        }

        System.out.println();
        System.out.println("Skipped files:");

        for (DiscoveryResult result : skippedFiles) {

            System.out.println(
                    "- "
                            + result.getFilePath()
                                    .getFileName()
                            + " | "
                            + result.getReason()
            );
        }
    }

    private void printFailedFiles(
            List<DiscoveryResult> files) {

        List<DiscoveryResult> failedFiles =
                files.stream()
                        .filter(result ->
                                result.getStatus()
                                        == DiscoveryStatus.FAILED)
                        .toList();

        if (failedFiles.isEmpty()) {
            return;
        }

        System.out.println();
        System.out.println("Failed files:");

        for (DiscoveryResult result : failedFiles) {

            System.out.println(
                    "- "
                            + result.getFilePath()
                                    .getFileName()
                            + " | "
                            + result.getReason()
                            + " | "
                            + result.getMessage()
            );
        }
    }
}
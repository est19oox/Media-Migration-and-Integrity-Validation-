package mmiv.model;

import java.util.List;

public class DiscoverySummary {

    private final long discovered;
    private final long accepted;
    private final long skipped;
    private final long failed;

    public DiscoverySummary(
            long discovered,
            long accepted,
            long skipped,
            long failed) {

        this.discovered = discovered;
        this.accepted = accepted;
        this.skipped = skipped;
        this.failed = failed;
    }

    public static DiscoverySummary from(
            List<DiscoveryResult> results) {

        long discovered =
                results.size();

        long accepted =
                results.stream()
                        .filter(result ->
                                result.getStatus()
                                        == DiscoveryStatus.ACCEPTED)
                        .count();

        long skipped =
                results.stream()
                        .filter(result ->
                                result.getStatus()
                                        == DiscoveryStatus.SKIPPED)
                        .count();

        long failed =
                results.stream()
                        .filter(result ->
                                result.getStatus()
                                        == DiscoveryStatus.FAILED)
                        .count();

        return new DiscoverySummary(
                discovered,
                accepted,
                skipped,
                failed
        );
    }

    public long getDiscovered() {
        return discovered;
    }

    public long getAccepted() {
        return accepted;
    }

    public long getSkipped() {
        return skipped;
    }

    public long getFailed() {
        return failed;
    }

    @Override
    public String toString() {
        return "DiscoverySummary{" +
                "discovered=" + discovered +
                ", accepted=" + accepted +
                ", skipped=" + skipped +
                ", failed=" + failed +
                '}';
    }
}
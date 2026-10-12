package mmiv.reporting.report;

import java.util.Objects;

public record MigrationSource(
        String type,
        String rootId,
        String manifestName
) {
    public MigrationSource {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(rootId, "rootId must not be null");
    }
}

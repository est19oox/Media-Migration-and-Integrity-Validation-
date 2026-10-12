package mmiv.reporting.contract;

public enum EventType {

    MIGRATION_STARTED(
            SourceService.DISCOVERY_SERVICE,
            "migration.started",
            false
    ),
    FILE_DISCOVERY_COMPLETED(
            SourceService.DISCOVERY_SERVICE,
            "discovery.file.completed",
            true
    ),
    MIGRATION_DISCOVERY_COMPLETED(
            SourceService.DISCOVERY_SERVICE,
            "discovery.migration.completed",
            false
    ),
    FILE_TRANSFER_COMPLETED(
            SourceService.TRANSFER_SERVICE,
            "transfer.file.completed",
            true
    ),
    INTEGRITY_VALIDATION_COMPLETED(
            SourceService.INTEGRITY_SERVICE,
            "integrity.validation.completed",
            true
    );

    private final SourceService publisher;
    private final String routingKey;
    private final boolean fileLevel;

    EventType(
            SourceService publisher,
            String routingKey,
            boolean fileLevel
    ) {
        this.publisher = publisher;
        this.routingKey = routingKey;
        this.fileLevel = fileLevel;
    }

    public SourceService publisher() {
        return publisher;
    }

    public String routingKey() {
        return routingKey;
    }

    public boolean fileLevel() {
        return fileLevel;
    }
}
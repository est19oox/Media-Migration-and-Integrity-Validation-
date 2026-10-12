package mmiv.reporting.contract;

import java.util.Arrays;

public enum SourceService {

    DISCOVERY_SERVICE("discovery-service"),
    TRANSFER_SERVICE("transfer-service"),
    INTEGRITY_SERVICE("integrity-service");

    private final String wireValue;

    SourceService(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SourceService fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(service -> service.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown sourceService: " + value
                        )
                );
    }
}
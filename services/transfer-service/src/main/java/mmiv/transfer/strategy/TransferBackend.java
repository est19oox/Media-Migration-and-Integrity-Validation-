package mmiv.transfer.strategy;

/** Storage transports supported by the server, independent of media type or input source. */
public enum TransferBackend {
    S3,
    // AZURE, // Alternate transport; enable only with a complete implementation.
}

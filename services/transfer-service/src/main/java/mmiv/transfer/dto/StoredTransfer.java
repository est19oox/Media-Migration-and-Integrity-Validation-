package mmiv.transfer.dto;

/** Storage receipt only; integrity and migration outcomes belong to TransferService. */
public record StoredTransfer(String bucket, String key, S3ObjectReference objectReference) {}

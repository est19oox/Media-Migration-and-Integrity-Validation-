package mmiv.transfer.dto;

/** Storage receipt only; integrity and migration decisions belong to the Transfer aggregate. */
public record StoredTransfer(String bucket, String key, S3ObjectReference objectReference) {}

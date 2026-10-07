package mmiv.transfer.files;

public record StoredFile(String bucket, String key, String fileName, long sizeBytes, String eTag) {}

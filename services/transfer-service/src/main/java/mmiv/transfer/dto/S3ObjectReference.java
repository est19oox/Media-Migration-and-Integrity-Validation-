package mmiv.transfer.dto;

/** Identity returned by this PutObject; cleanup must not delete a later overwrite. */
public record S3ObjectReference(String eTag, String versionId) {}

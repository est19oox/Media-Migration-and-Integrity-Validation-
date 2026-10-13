package mmiv.csv;

import java.time.LocalDate;

public record MetadataCsvRecord(
    String mediaId,
    String fileName,
    String mediaType,
    String format,
    long fileSize,
    LocalDate createdDate,
    String uploadedBy,
    String tags,
    String description) {}

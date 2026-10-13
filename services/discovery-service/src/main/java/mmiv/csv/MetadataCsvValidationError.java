package mmiv.csv;

/**
 * Represents one validation error found in a metadata CSV.
 *
 * @param rowNumber CSV row number, starting from 1
 * @param fieldName Name of the field containing the error
 * @param reason Description of the validation failure
 */
public record MetadataCsvValidationError(long rowNumber, String fieldName, String reason) {}

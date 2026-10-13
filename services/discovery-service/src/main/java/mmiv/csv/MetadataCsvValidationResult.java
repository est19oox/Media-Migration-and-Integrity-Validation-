package mmiv.csv;

import java.util.List;

/** Represents the overall validation result of a CSV manifest. */
public record MetadataCsvValidationResult(boolean valid, List<MetadataCsvValidationError> errors) {

  public MetadataCsvValidationResult {
    errors = List.copyOf(errors);

    if (valid != errors.isEmpty()) {
      throw new IllegalArgumentException("Validation status must match the error list");
    }
  }
}

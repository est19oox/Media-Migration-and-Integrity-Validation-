
package mmiv.csv;

import java.util.List;

public class MetadataCsvValidationException
        extends IllegalArgumentException {

    private final List<MetadataCsvValidationError> errors;

    public MetadataCsvValidationException(
            List<MetadataCsvValidationError> errors
    ) {
        super("Metadata CSV validation failed with "
                + errors.size() + " error(s)");

        this.errors = List.copyOf(errors);
    }

    public List<MetadataCsvValidationError> getErrors() {
        return errors;
    }
}

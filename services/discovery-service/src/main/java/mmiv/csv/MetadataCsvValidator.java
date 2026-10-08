package mmiv.csv;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class MetadataCsvValidator {

    private static final List<String> EXPECTED_HEADERS = List.of(
            "Media ID",
            "File Name",
            "Media Type",
            "Format",
            "File Size",
            "Created Date",
            "Uploaded By",
            "Tags",
            "Description"
    );

    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "Media ID",
            "File Name",
            "Media Type",
            "Format",
            "File Size",
            "Created Date",
            "Uploaded By"
    );

    public MetadataCsvValidationResult validate(Path inputPath) {

        List<MetadataCsvValidationError> errors =
                new ArrayList<>();

        if (inputPath == null) {
            errors.add(new MetadataCsvValidationError(
                    0, "File", "Input path cannot be null"
            ));
            return result(errors);
        }

        if (!Files.isRegularFile(inputPath)) {
            errors.add(new MetadataCsvValidationError(
                    0, "File", "CSV file does not exist"
            ));
            return result(errors);
        }

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setIgnoreEmptyLines(true)
                .get();

        try (
                Reader reader = Files.newBufferedReader(
                        inputPath,
                        StandardCharsets.UTF_8
                );
                CSVParser parser = format.parse(reader)
        ) {

            List<CSVRecord> rows = parser.getRecords();

            if (rows.isEmpty()) {
                errors.add(new MetadataCsvValidationError(
                        1, "Header", "CSV file is empty"
                ));
                return result(errors);
            }

            CSVRecord headerRow = rows.get(0);

            List<String> actualHeaders =
                    Arrays.asList(headerRow.values());

            if (!EXPECTED_HEADERS.equals(actualHeaders)) {
                errors.add(new MetadataCsvValidationError(
                        1,
                        "Header",
                        "CSV headers must match the required nine columns"
                ));
                return result(errors);
            }

            Set<String> mediaIds = new HashSet<>();

            for (int i = 1; i < rows.size(); i++) {

                CSVRecord row = rows.get(i);
                long rowNumber = row.getRecordNumber();

                if (row.size() != EXPECTED_HEADERS.size()) {
                    errors.add(new MetadataCsvValidationError(
                            rowNumber,
                            "Row",
                            "Expected 9 columns but found " + row.size()
                    ));
                    continue;
                }

                for (int column = 0;
                     column < EXPECTED_HEADERS.size();
                     column++) {

                    String fieldName = EXPECTED_HEADERS.get(column);
                    String value = row.get(column).trim();

                    if (REQUIRED_FIELDS.contains(fieldName)
                            && value.isEmpty()) {

                        errors.add(new MetadataCsvValidationError(
                                rowNumber,
                                fieldName,
                                fieldName + " is required"
                        ));
                    }
                }

                String mediaId = row.get(0).trim();

                if (!mediaId.isEmpty()
                        && !mediaIds.add(mediaId)) {

                    errors.add(new MetadataCsvValidationError(
                            rowNumber,
                            "Media ID",
                            "Duplicate Media ID: " + mediaId
                    ));
                }

                String fileSize = row.get(4).trim();

                if (!fileSize.isEmpty()) {
                    try {
                        long size = Long.parseLong(fileSize);

                        if (size < 0) {
                            errors.add(new MetadataCsvValidationError(
                                    rowNumber,
                                    "File Size",
                                    "File Size cannot be negative"
                            ));
                        }

                    } catch (NumberFormatException e) {
                        errors.add(new MetadataCsvValidationError(
                                rowNumber,
                                "File Size",
                                "Invalid numeric value: " + fileSize
                        ));
                    }
                }

                String createdDate = row.get(5).trim();

                if (!createdDate.isEmpty()) {
                    try {
                        LocalDate.parse(createdDate);

                    } catch (DateTimeParseException e) {
                        errors.add(new MetadataCsvValidationError(
                                rowNumber,
                                "Created Date",
                                "Invalid date format. Expected yyyy-MM-dd"
                        ));
                    }
                }
            }

        } catch (IOException | IllegalArgumentException e) {

            errors.add(new MetadataCsvValidationError(
                    0,
                    "File",
                    "Unable to read CSV: " + e.getMessage()
            ));
        }

        return result(errors);
    }

    private MetadataCsvValidationResult result(
            List<MetadataCsvValidationError> errors
    ) {
        return new MetadataCsvValidationResult(
                errors.isEmpty(),
                errors
        );
    }
}

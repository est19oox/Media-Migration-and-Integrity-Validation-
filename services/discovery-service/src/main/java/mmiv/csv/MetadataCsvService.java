
package mmiv.csv;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.io.Reader;
import java.util.ArrayList;

import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;

@Service
public class MetadataCsvService {
    private final MetadataCsvValidator validator;

    public MetadataCsvService(
            MetadataCsvValidator validator
    ) {
        this.validator = validator;
    }

    private static final String[] HEADERS = {
            "Media ID",
            "File Name",
            "Media Type",
            "Format",
            "File Size",
            "Created Date",
            "Uploaded By",
            "Tags",
            "Description"
    };

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE;

    private static final CSVFormat CSV_FORMAT =
            CSVFormat.DEFAULT.builder()
                    .setHeader(HEADERS)
                    .get();

    /**
     * Generate an empty CSV template with nine headers.
     */
    public void generateTemplate(Path outputPath) throws IOException {
        writeCsv(outputPath, List.of());
    }

    /**
     * Generate a CSV file from metadata records.
     */
    public void generateCsv(
            Path outputPath,
            List<MetadataCsvRecord> records
    ) throws IOException {

        if (records == null) {
            throw new IllegalArgumentException(
                    "Metadata records cannot be null"
            );
        }

        writeCsv(outputPath, records);
    }

    /**
     * Write metadata records to a CSV file.
     */
    private void writeCsv(
            Path outputPath,
            List<MetadataCsvRecord> records
    ) throws IOException {

        if (outputPath == null) {
            throw new IllegalArgumentException(
                    "Output path cannot be null"
            );
        }

        Path parent = outputPath.toAbsolutePath().getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (
                Writer writer = Files.newBufferedWriter(
                        outputPath,
                        StandardCharsets.UTF_8
                );

                CSVPrinter printer = new CSVPrinter(
                        writer,
                        CSV_FORMAT
                )
        ) {

            for (MetadataCsvRecord record : records) {

                if (record == null) {
                    throw new IllegalArgumentException(
                            "Metadata record cannot be null"
                    );
                }

                printer.printRecord(
                        record.mediaId(),
                        record.fileName(),
                        record.mediaType(),
                        record.format(),
                        record.fileSize(),
                        record.createdDate() == null
                                ? ""
                                : record.createdDate().format(DATE_FORMAT),
                        record.uploadedBy(),
                        record.tags(),
                        record.description()
                );
            }
        }
    }

    /**
     * Read a metadata CSV manifest and convert its rows
     * into MetadataCsvRecord objects.
     */
    public List<MetadataCsvRecord> parseCsv(Path inputPath)
            throws IOException {

        if (inputPath == null) {
            throw new IllegalArgumentException(
                    "Input path cannot be null"
            );
        }

        List<MetadataCsvRecord> records = new ArrayList<>();

        CSVFormat inputFormat = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .get();

        try (
                Reader reader = Files.newBufferedReader(
                        inputPath,
                        StandardCharsets.UTF_8
                );
                CSVParser parser = inputFormat.parse(reader)
        ) {

            for (org.apache.commons.csv.CSVRecord row : parser) {

                MetadataCsvRecord record = new MetadataCsvRecord(
                        row.get("Media ID"),
                        row.get("File Name"),
                        row.get("Media Type"),
                        row.get("Format"),
                        Long.parseLong(row.get("File Size")),
                        java.time.LocalDate.parse(
                                row.get("Created Date"),
                                DATE_FORMAT
                        ),
                        row.get("Uploaded By"),
                        row.get("Tags"),
                        row.get("Description")
                );

                records.add(record);
            }
        }

        return records;
    }

    /**
     * Validate the CSV manifest before parsing.
     *
     * Invalid CSV files will not be parsed or sent
     * to the Discovery workflow.
     */
    public List<MetadataCsvRecord> parseValidatedCsv(
            Path inputPath
    ) throws IOException {

        MetadataCsvValidationResult validationResult =
                validator.validate(inputPath);

        if (!validationResult.valid()) {
            throw new MetadataCsvValidationException(
                    validationResult.errors()
            );
        }

        return parseCsv(inputPath);
    }
}

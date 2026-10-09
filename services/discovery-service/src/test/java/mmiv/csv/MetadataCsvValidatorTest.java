
package mmiv.csv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MetadataCsvValidatorTest {

    @TempDir
    Path tempDir;

    private MetadataCsvValidator validator;

    private static final String HEADER =
            "Media ID,File Name,Media Type,Format,File Size,"
            + "Created Date,Uploaded By,Tags,Description\n";

    @BeforeEach
    void setUp() {
        validator = new MetadataCsvValidator();
    }

    private MetadataCsvValidationResult validateContent(
            String content
    ) throws Exception {

        Path input = tempDir.resolve("metadata.csv");

        Files.writeString(
                input,
                content,
                StandardCharsets.UTF_8
        );

        return validator.validate(input);
    }

    private void assertHasError(
            MetadataCsvValidationResult result,
            String field,
            long row
    ) {
        assertFalse(result.valid());

        assertTrue(
                result.errors().stream().anyMatch(error ->
                        error.fieldName().equals(field)
                        && error.rowNumber() == row
                ),
                "Expected error for " + field + " at row " + row
        );
    }

    @Test
    void shouldAcceptValidCsv() throws Exception {

        String content = HEADER
                + "M001,video01.mp4,VIDEO,MP4,10500000,"
                + "2026-10-05,user01,lecture,Week 1 video\n";

        MetadataCsvValidationResult result =
                validateContent(content);

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void shouldRejectIncorrectHeaders() throws Exception {

        String content =
                "ID,Name,Type,Format,Size,Date,User,Tags,Description\n"
                + "M001,video.mp4,VIDEO,MP4,100,"
                + "2026-10-05,user01,test,Demo\n";

        MetadataCsvValidationResult result =
                validateContent(content);

        assertHasError(result, "Header", 1);
    }

    @Test
    void shouldRejectMissingRequiredField() throws Exception {

        String content = HEADER
                + "M001,,VIDEO,MP4,100,"
                + "2026-10-05,user01,test,Demo\n";

        MetadataCsvValidationResult result =
                validateContent(content);

        assertHasError(result, "File Name", 2);
    }

    @Test
    void shouldRejectInvalidFileSize() throws Exception {

        String content = HEADER
                + "M001,video.mp4,VIDEO,MP4,abc,"
                + "2026-10-05,user01,test,Demo\n";

        MetadataCsvValidationResult result =
                validateContent(content);

        assertHasError(result, "File Size", 2);
    }

    @Test
    void shouldRejectNegativeFileSize() throws Exception {

        String content = HEADER
                + "M001,video.mp4,VIDEO,MP4,-100,"
                + "2026-10-05,user01,test,Demo\n";

        MetadataCsvValidationResult result =
                validateContent(content);

        assertHasError(result, "File Size", 2);
    }

    @Test
    void shouldRejectInvalidDate() throws Exception {

        String content = HEADER
                + "M001,video.mp4,VIDEO,MP4,100,"
                + "not-a-date,user01,test,Demo\n";

        MetadataCsvValidationResult result =
                validateContent(content);

        assertHasError(result, "Created Date", 2);
    }

    @Test
    void shouldRejectDuplicateMediaId() throws Exception {

        String content = HEADER
                + "M001,video01.mp4,VIDEO,MP4,100,"
                + "2026-10-05,user01,test,Demo\n"
                + "M001,video02.mp4,VIDEO,MP4,200,"
                + "2026-10-05,user01,test,Demo\n";

        MetadataCsvValidationResult result =
                validateContent(content);

        assertHasError(result, "Media ID", 3);
    }

    @Test
    void shouldRejectIncorrectColumnCount() throws Exception {

        String content = HEADER
                + "M001,video.mp4,VIDEO,MP4,100\n";

        MetadataCsvValidationResult result =
                validateContent(content);

        assertHasError(result, "Row", 2);
    }

    @Test
    void shouldRejectMissingFile() {

        Path missing = tempDir.resolve("missing.csv");

        MetadataCsvValidationResult result =
                validator.validate(missing);

        assertHasError(result, "File", 0);
    }

    @Test
    void shouldRejectNullInputPath() {

        MetadataCsvValidationResult result =
                validator.validate(null);

        assertHasError(result, "File", 0);
    }

    @Test
    void shouldAcceptEmptyOptionalFields() throws Exception {

        String content = HEADER
                + "M001,video.mp4,VIDEO,MP4,100,"
                + "2026-10-05,user01,,\n";

        MetadataCsvValidationResult result =
                validateContent(content);

        assertTrue(result.valid());
    }

    @Test
    void shouldReportMultipleErrors() throws Exception {

        String content = HEADER
                + "M001,,VIDEO,MP4,abc,"
                + "invalid-date,user01,test,Demo\n";

        MetadataCsvValidationResult result =
                validateContent(content);

        assertFalse(result.valid());
        assertEquals(3, result.errors().size());

        assertHasError(result, "File Name", 2);
        assertHasError(result, "File Size", 2);
        assertHasError(result, "Created Date", 2);
    }
}

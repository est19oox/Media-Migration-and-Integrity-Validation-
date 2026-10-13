package mmiv.csv;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataCsvServiceTest {

  @TempDir Path tempDir;

  private MetadataCsvService csvService;

  @BeforeEach
  void setUp() {
    csvService = new MetadataCsvService(new MetadataCsvValidator());
  }

  @Test
  void shouldGenerateTemplateWithNineHeaders() throws Exception {

    Path output = tempDir.resolve("template.csv");

    csvService.generateTemplate(output);

    assertTrue(Files.exists(output));

    try (CSVParser parser = CSVParser.parse(output, StandardCharsets.UTF_8, CSVFormat.DEFAULT)) {

      List<CSVRecord> rows = parser.getRecords();

      assertEquals(1, rows.size());

      CSVRecord header = rows.get(0);

      assertEquals(9, header.size());
      assertEquals("Media ID", header.get(0));
      assertEquals("File Name", header.get(1));
      assertEquals("Media Type", header.get(2));
      assertEquals("Format", header.get(3));
      assertEquals("File Size", header.get(4));
      assertEquals("Created Date", header.get(5));
      assertEquals("Uploaded By", header.get(6));
      assertEquals("Tags", header.get(7));
      assertEquals("Description", header.get(8));
    }
  }

  @Test
  void shouldGenerateCsvWithMetadataRecords() throws Exception {

    Path output = tempDir.resolve("metadata.csv");

    MetadataCsvRecord record =
        new MetadataCsvRecord(
            "M001",
            "video01.mp4",
            "VIDEO",
            "MP4",
            10500000L,
            LocalDate.of(2026, 10, 5),
            "user01",
            "lecture",
            "Week 1 video");

    csvService.generateCsv(output, List.of(record));

    try (CSVParser parser = CSVParser.parse(output, StandardCharsets.UTF_8, CSVFormat.DEFAULT)) {

      List<CSVRecord> rows = parser.getRecords();

      assertEquals(2, rows.size());

      CSVRecord data = rows.get(1);

      assertEquals("M001", data.get(0));
      assertEquals("video01.mp4", data.get(1));
      assertEquals("VIDEO", data.get(2));
      assertEquals("MP4", data.get(3));
      assertEquals("10500000", data.get(4));
      assertEquals("2026-10-05", data.get(5));
      assertEquals("user01", data.get(6));
      assertEquals("lecture", data.get(7));
      assertEquals("Week 1 video", data.get(8));
    }
  }

  @Test
  void shouldHandleCommaInDescription() throws Exception {

    Path output = tempDir.resolve("special.csv");

    MetadataCsvRecord record =
        new MetadataCsvRecord(
            "M002",
            "photo01.jpg",
            "IMAGE",
            "JPG",
            2500000L,
            LocalDate.of(2026, 10, 5),
            "user01",
            "photo,course",
            "Course photo, Week 1");

    csvService.generateCsv(output, List.of(record));

    try (CSVParser parser = CSVParser.parse(output, StandardCharsets.UTF_8, CSVFormat.DEFAULT)) {

      List<CSVRecord> rows = parser.getRecords();

      assertEquals(2, rows.size());
      assertEquals(9, rows.get(1).size());

      assertEquals("photo,course", rows.get(1).get(7));

      assertEquals("Course photo, Week 1", rows.get(1).get(8));
    }
  }

  @Test
  void shouldRejectNullRecords() {

    Path output = tempDir.resolve("invalid.csv");

    assertThrows(IllegalArgumentException.class, () -> csvService.generateCsv(output, null));
  }

  @Test
  void shouldRejectNullOutputPath() {

    assertThrows(IllegalArgumentException.class, () -> csvService.generateTemplate(null));
  }

  @Test
  void shouldParseCsvWithMetadataRecords() throws Exception {

    Path input = tempDir.resolve("input.csv");

    String content =
        """
                Media ID,File Name,Media Type,Format,File Size,Created Date,Uploaded By,Tags,Description
                M001,video01.mp4,VIDEO,MP4,10500000,2026-10-05,user01,lecture,Week 1 video
                M002,photo01.jpg,IMAGE,JPG,2500000,2026-10-05,user01,photo,Course photo
                """;

    Files.writeString(input, content, StandardCharsets.UTF_8);

    List<MetadataCsvRecord> records = csvService.parseCsv(input);

    assertEquals(2, records.size());

    MetadataCsvRecord first = records.get(0);

    assertEquals("M001", first.mediaId());
    assertEquals("video01.mp4", first.fileName());
    assertEquals("VIDEO", first.mediaType());
    assertEquals("MP4", first.format());
    assertEquals(10500000L, first.fileSize());
    assertEquals(LocalDate.of(2026, 10, 5), first.createdDate());
    assertEquals("user01", first.uploadedBy());
    assertEquals("lecture", first.tags());
    assertEquals("Week 1 video", first.description());

    assertEquals("M002", records.get(1).mediaId());
    assertEquals("photo01.jpg", records.get(1).fileName());
  }

  @Test
  void shouldParseCsvWithCommaInDescription() throws Exception {

    Path input = tempDir.resolve("comma.csv");

    String content =
        """
                Media ID,File Name,Media Type,Format,File Size,Created Date,Uploaded By,Tags,Description
                M003,lecture.mp4,VIDEO,MP4,5000000,2026-10-05,user01,"lecture,week1","Introduction, Week 1"
                """;

    Files.writeString(input, content, StandardCharsets.UTF_8);

    List<MetadataCsvRecord> records = csvService.parseCsv(input);

    assertEquals(1, records.size());
    assertEquals("lecture,week1", records.get(0).tags());
    assertEquals("Introduction, Week 1", records.get(0).description());
  }

  @Test
  void shouldGenerateAndParseCsvConsistently() throws Exception {

    Path output = tempDir.resolve("roundtrip.csv");

    MetadataCsvRecord original =
        new MetadataCsvRecord(
            "M004",
            "demo.mp4",
            "VIDEO",
            "MP4",
            8000000L,
            LocalDate.of(2026, 10, 6),
            "user02",
            "demo,testing",
            "Demo video, with description");

    csvService.generateCsv(output, List.of(original));

    List<MetadataCsvRecord> parsed = csvService.parseCsv(output);

    assertEquals(1, parsed.size());
    assertEquals(original, parsed.get(0));
  }

  @Test
  void shouldRejectNullInputPath() {

    assertThrows(IllegalArgumentException.class, () -> csvService.parseCsv(null));
  }

  @Test
  void shouldParseValidCsvAfterValidation() throws Exception {

    Path input = tempDir.resolve("valid.csv");

    String content =
        """
                Media ID,File Name,Media Type,Format,File Size,Created Date,Uploaded By,Tags,Description
                M001,video01.mp4,VIDEO,MP4,10500000,2026-10-05,user01,lecture,Week 1 video
                """;

    Files.writeString(input, content, StandardCharsets.UTF_8);

    List<MetadataCsvRecord> records = csvService.parseValidatedCsv(input);

    assertEquals(1, records.size());
    assertEquals("M001", records.get(0).mediaId());
  }

  @Test
  void shouldRejectInvalidCsvBeforeParsing() throws Exception {

    Path input = tempDir.resolve("invalid.csv");

    String content =
        """
                Media ID,File Name,Media Type,Format,File Size,Created Date,Uploaded By,Tags,Description
                M002,video02.mp4,VIDEO,MP4,abc,2026-10-05,user01,test,Demo
                """;

    Files.writeString(input, content, StandardCharsets.UTF_8);

    MetadataCsvValidationException exception =
        assertThrows(
            MetadataCsvValidationException.class, () -> csvService.parseValidatedCsv(input));

    assertEquals(1, exception.getErrors().size());
    assertEquals("File Size", exception.getErrors().get(0).fieldName());
    assertEquals(2, exception.getErrors().get(0).rowNumber());
  }

  @Test
  void shouldRejectMultipleCsvErrorsBeforeParsing() throws Exception {

    Path input = tempDir.resolve("multiple-errors.csv");

    String content =
        """
                Media ID,File Name,Media Type,Format,File Size,Created Date,Uploaded By,Tags,Description
                M003,,VIDEO,MP4,abc,invalid-date,user01,test,Demo
                """;

    Files.writeString(input, content, StandardCharsets.UTF_8);

    MetadataCsvValidationException exception =
        assertThrows(
            MetadataCsvValidationException.class, () -> csvService.parseValidatedCsv(input));

    assertEquals(3, exception.getErrors().size());
  }
}

package mmiv.reporting.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class EventContractSchemaTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final EventContractValidator validator = new EventContractValidator(objectMapper);

  @Test
  void publishesVersionTwoEnvelopeRequirements() throws Exception {
    try (InputStream schemaStream =
        getClass().getResourceAsStream("/contracts/events/v2/mmiv-event.schema.json")) {

      assertNotNull(
          schemaStream, "The v2 event contract must be published as a classpath resource");

      JsonNode schema = objectMapper.readTree(schemaStream);

      assertEquals(2, schema.path("properties").path("schemaVersion").path("const").intValue());

      Set<String> requiredFields = new HashSet<>();
      schema.path("required").forEach(node -> requiredFields.add(node.stringValue()));

      assertEquals(
          Set.of(
              "eventId",
              "eventType",
              "schemaVersion",
              "sourceService",
              "migrationId",
              "occurredAt",
              "data"),
          requiredFields);
    }
  }

  @Test
  void classpathSchemaMatchesThePublishedRepositoryContract() throws Exception {
    Path publishedSchema = repositoryRoot().resolve("contracts/events/v2/mmiv-event.schema.json");

    assertTrue(
        Files.isRegularFile(publishedSchema), "The repository-level v2 schema must be published");

    try (InputStream resourceSchema =
        getClass().getResourceAsStream("/contracts/events/v2/mmiv-event.schema.json")) {
      assertNotNull(resourceSchema);
      assertEquals(
          objectMapper.readTree(publishedSchema.toFile()),
          objectMapper.readTree(resourceSchema),
          "The runtime and repository schemas must not drift");
    }
  }

  @Test
  void allSixPublishedExamplesPassRuntimeValidation() throws Exception {
    Path examplesDirectory = repositoryRoot().resolve("contracts/events/v2/examples");

    List<Path> examples;
    try (var paths = Files.list(examplesDirectory)) {
      examples =
          paths.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
    }

    assertEquals(6, examples.size(), "Exactly six approved v2 examples must be published");

    for (Path example : examples) {
      ValidatedEvent validated = validator.validate(Files.readAllBytes(example));
      assertEquals(2, validated.envelope().schemaVersion());
    }
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();

    while (current != null) {
      if (Files.isDirectory(current.resolve("contracts"))
          || Files.isDirectory(current.resolve("services"))) {
        return current;
      }
      current = current.getParent();
    }

    throw new IllegalStateException("Could not locate the repository root");
  }
}

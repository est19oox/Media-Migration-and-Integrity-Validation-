package mmiv.reporting.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import mmiv.reporting.audit.AuditEventDocument;
import mmiv.reporting.audit.AuditEventRepository;
import mmiv.reporting.contract.EventContractValidator;
import mmiv.reporting.report.MigrationReportDocument;
import mmiv.reporting.report.MigrationReportRepository;
import mmiv.reporting.report.MigrationSource;
import tools.jackson.databind.json.JsonMapper;

class ReportControllerTest {

    private static final UUID MIGRATION_ID =
            UUID.fromString(
                    "c49f2f8f-ad97-45a8-8867-f3ca8a971d80"
            );

    private MigrationReportRepository reportRepository;

    private AuditEventRepository auditRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reportRepository =
                mock(MigrationReportRepository.class);
        auditRepository =
                mock(AuditEventRepository.class);

        ReportController controller = new ReportController(
                reportRepository,
                auditRepository
        );

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(
                        new ReportingExceptionHandler()
                )
                .build();
    }

    @Test
    void knownReportReturnsDtoWithoutPersistenceInternals()
            throws Exception {
        when(reportRepository.findById(
                MIGRATION_ID.toString()
        )).thenReturn(Optional.of(report()));

        mockMvc.perform(get(
                        "/api/reports/{migrationId}",
                        MIGRATION_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migrationId")
                        .value(MIGRATION_ID.toString()))
                .andExpect(jsonPath("$.status")
                        .value("PROCESSING"))
                .andExpect(jsonPath("$.initiatedBy")
                        .value("user-123"))
                .andExpect(jsonPath("$.source.type")
                        .value("MANIFEST"))
                .andExpect(jsonPath("$._class")
                        .doesNotExist())
                .andExpect(jsonPath("$.payloadSha256")
                        .doesNotExist())
                .andExpect(jsonPath("$.processingStatus")
                        .doesNotExist());
    }

    @Test
    void unknownReportReturns404() throws Exception {
        when(reportRepository.findById(
                MIGRATION_ID.toString()
        )).thenReturn(Optional.empty());

        mockMvc.perform(get(
                        "/api/reports/{migrationId}",
                        MIGRATION_ID
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("REPORT_NOT_FOUND"));
    }

    @Test
    void reportListSupportsValidPaging() throws Exception {
        when(reportRepository.findAll(any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(0);
                    return new PageImpl<>(
                            List.of(report()),
                            pageable,
                            1
                    );
                });

        mockMvc.perform(get("/api/reports")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].migrationId")
                        .value(MIGRATION_ID.toString()));
    }

    @Test
    void invalidPagingReturns400() throws Exception {
        mockMvc.perform(get("/api/reports")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/reports")
                        .param("size", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/reports")
                        .param("size", "201"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fileResultsAreOrderedByFileId() throws Exception {
        MigrationReportDocument report = report();
        UUID later = UUID.fromString(
                "ffffffff-ffff-ffff-ffff-ffffffffffff"
        );
        UUID earlier = UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
        );
        report.getOrCreateFile(later).recordTransferStored();
        report.getOrCreateFile(earlier).recordDiscoveryAccepted();

        when(reportRepository.findById(
                MIGRATION_ID.toString()
        )).thenReturn(Optional.of(report));

        mockMvc.perform(get(
                        "/api/reports/{migrationId}/files",
                        MIGRATION_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].fileId")
                        .value(earlier.toString()))
                .andExpect(jsonPath("$.content[1].fileId")
                        .value(later.toString()));
    }

    @Test
    void auditEventsExcludeHashAndProcessingMetadata()
            throws Exception {
        when(reportRepository.existsById(
                MIGRATION_ID.toString()
        )).thenReturn(true);
        when(auditRepository
                .findByMigrationIdOrderByOccurredAtAsc(
                        eq(MIGRATION_ID.toString()),
                        any(Pageable.class)
                )).thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(1);
                    return new PageImpl<>(
                            List.of(auditEvent()),
                            pageable,
                            1
                    );
                });

        mockMvc.perform(get(
                        "/api/reports/{migrationId}/events",
                        MIGRATION_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventId")
                        .value(
                                "5bd98576-0707-4b83-850d-945dfe33e606"
                        ))
                .andExpect(jsonPath(
                        "$.content[0].payloadSha256"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.content[0].processingStatus"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.content[0].originalBody"
                ).doesNotExist());
    }

    private MigrationReportDocument report() {
        MigrationReportDocument report =
                MigrationReportDocument.processing(
                MIGRATION_ID,
                Instant.parse("2026-10-11T10:00:00Z")
        );
        report.recordStarted(
                Instant.parse("2026-10-11T10:00:00Z"),
                "user-123",
                new MigrationSource(
                        "MANIFEST",
                        "legacy-media",
                        "semester-1-media.csv"
                )
        );
        return report;
    }

    private AuditEventDocument auditEvent() {
        byte[] body = """
                {
                  "eventId": "5bd98576-0707-4b83-850d-945dfe33e606",
                  "eventType": "MIGRATION_STARTED",
                  "schemaVersion": 2,
                  "sourceService": "discovery-service",
                  "migrationId": "c49f2f8f-ad97-45a8-8867-f3ca8a971d80",
                  "occurredAt": "2026-10-11T10:00:00Z",
                  "data": {
                    "initiatedBy": "user-123",
                    "source": {
                      "type": "MANIFEST",
                      "rootId": "legacy-media",
                      "manifestName": "semester-1-media.csv"
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);

        return AuditEventDocument.received(
                new EventContractValidator(
                        JsonMapper.builder().build()
                ).validate(body),
                Instant.parse("2026-10-11T10:00:01Z")
        );
    }
}

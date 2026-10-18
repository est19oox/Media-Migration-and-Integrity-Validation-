package mmiv.integration;

import org.springframework.jdbc.core.JdbcTemplate;

/** One-time, restart-safe copy of Discovery-owned rows from the former shared schema. */
public final class LegacyDiscoveryDataMigration {
  private LegacyDiscoveryDataMigration() {}

  public static void copy(JdbcTemplate jdbc, String table, String columns) {
    if (!java.util.Set.of(
            "MMIV_NAME_LOCKS",
            "MMIV_NAME_RESERVATIONS",
            "MMIV_TRANSFER_PLANS",
            "MMIV_VERIFICATION_RUNS",
            "FILE_METADATA",
            "MEDIA_LOG")
        .contains(table)) throw new IllegalArgumentException("Unknown Discovery table");
    jdbc.execute(
        "DECLARE BEGIN EXECUTE IMMEDIATE 'CREATE TABLE MMIV_SCHEMA_MIGRATIONS"
            + " (TABLE_NAME VARCHAR2(40) PRIMARY KEY)'; EXCEPTION WHEN OTHERS THEN"
            + " IF SQLCODE != -955 THEN RAISE; END IF; END;");
    Integer legacy =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER='USER_ACCESS' AND TABLE_NAME=?",
            Integer.class,
            table);
    if (legacy == null || legacy == 0) return;
    // The insert and marker commit as one JDBC statement. A retry after interruption
    // cannot duplicate rows, including tables with generated identity columns.
    jdbc.execute(
        "DECLARE n NUMBER; BEGIN SELECT COUNT(*) INTO n FROM MMIV_SCHEMA_MIGRATIONS"
            + " WHERE TABLE_NAME='"
            + table
            + "'; IF n=0 THEN INSERT INTO "
            + table
            + " ("
            + columns
            + ") SELECT "
            + columns
            + " FROM USER_ACCESS."
            + table
            + "; INSERT INTO MMIV_SCHEMA_MIGRATIONS(TABLE_NAME) VALUES ('"
            + table
            + "'); END IF; END;");
  }
}

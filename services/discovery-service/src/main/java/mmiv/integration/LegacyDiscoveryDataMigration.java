package mmiv.integration;

import org.springframework.jdbc.core.JdbcTemplate;

/** One-time, restart-safe copy of Discovery-owned rows from the former shared schema. */
public final class LegacyDiscoveryDataMigration {
  private LegacyDiscoveryDataMigration() {}

  /** Only these fixed schema identifiers can be used in the migration SQL. */
  public enum Table {
    MMIV_NAME_LOCKS("FOLDER"),
    MMIV_NAME_RESERVATIONS("FOLDER,OBJECT_PATH,PLAN_ID"),
    MMIV_TRANSFER_PLANS("PLAN_ID,OWNER_ID,PLAN_JSON,EXPIRES_AT"),
    MMIV_VERIFICATION_RUNS("OWNER_ID,RUN_ID,CLOSED"),
    FILE_METADATA("FILE_NAME,MEDIA_TYPE,FILE_SIZE,SRC_DIR,CREATED_DATE"),
    MEDIA_LOG(
        "FILE_NAME,FILE_TYPE,FILE_DIR,FILE_SIZE,VALIDATION_STATUS,CHECK_FAILED,EXPECTED_VALUE,CHECKED_AT");

    private final String copySql;

    Table(String columns) {
      String table = name();
      // Oracle identifiers cannot be bound. Both identifiers come only from enum constants.
      this.copySql =
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
              + "'); END IF; END;";
    }
  }

  public static void copy(JdbcTemplate jdbc, Table table) {
    jdbc.execute(
        "DECLARE BEGIN EXECUTE IMMEDIATE 'CREATE TABLE MMIV_SCHEMA_MIGRATIONS"
            + " (TABLE_NAME VARCHAR2(40) PRIMARY KEY)'; EXCEPTION WHEN OTHERS THEN"
            + " IF SQLCODE != -955 THEN RAISE; END IF; END;");
    Integer legacy =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER='USER_ACCESS' AND TABLE_NAME=?",
            Integer.class,
            table.name());
    if (legacy == null || legacy == 0) return;
    // The insert and marker commit as one JDBC statement. A retry after interruption
    // cannot duplicate rows, including tables with generated identity columns.
    jdbc.execute(table.copySql);
  }
}

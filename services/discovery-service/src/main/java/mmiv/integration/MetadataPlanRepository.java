package mmiv.integration;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import mmiv.model.MetadataPlan;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class MetadataPlanRepository implements InitializingBean {
  private final JdbcTemplate jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  public MetadataPlanRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void afterPropertiesSet() {
    createTable("CREATE TABLE MMIV_NAME_LOCKS (FOLDER VARCHAR2(80) PRIMARY KEY)");
    createTable(
        "CREATE TABLE MMIV_NAME_RESERVATIONS (FOLDER VARCHAR2(80) NOT NULL, OBJECT_PATH"
            + " VARCHAR2(900 BYTE) NOT NULL, PLAN_ID VARCHAR2(36) NOT NULL, PRIMARY"
            + " KEY(FOLDER,OBJECT_PATH))");
    createTable(
        "CREATE TABLE FILE_METADATA (FILE_ID NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
            + " FILE_NAME VARCHAR2(255) NOT NULL, MEDIA_TYPE VARCHAR2(100), FILE_SIZE NUMBER,"
            + " SRC_DIR VARCHAR2(2000), CREATED_DATE TIMESTAMP, CONSTRAINT"
            + " MMIV_FILE_LOCATION_UNIQUE UNIQUE(FILE_NAME,SRC_DIR))");
    createTable(
        "CREATE TABLE MEDIA_LOG (LOG_ID NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, FILE_NAME"
            + " VARCHAR2(255), FILE_TYPE VARCHAR2(100), FILE_DIR VARCHAR2(2000), FILE_SIZE NUMBER,"
            + " VALIDATION_STATUS VARCHAR2(20), CHECK_FAILED VARCHAR2(255), EXPECTED_VALUE"
            + " VARCHAR2(2000), CHECKED_AT TIMESTAMP)");
    // Only ignore Oracle's table-already-exists error. Other schema errors abort startup.
    jdbc.execute(
        """
        DECLARE BEGIN
          EXECUTE IMMEDIATE 'CREATE TABLE MMIV_TRANSFER_PLANS (
            PLAN_ID VARCHAR2(36) PRIMARY KEY, OWNER_ID VARCHAR2(128) NOT NULL,
            PLAN_JSON CLOB NOT NULL, EXPIRES_AT TIMESTAMP NOT NULL)';
        EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
        """);
    LegacyDiscoveryDataMigration.copy(jdbc, "MMIV_NAME_LOCKS", "FOLDER");
    LegacyDiscoveryDataMigration.copy(jdbc, "MMIV_NAME_RESERVATIONS", "FOLDER,OBJECT_PATH,PLAN_ID");
    LegacyDiscoveryDataMigration.copy(
        jdbc, "MMIV_TRANSFER_PLANS", "PLAN_ID,OWNER_ID,PLAN_JSON,EXPIRES_AT");
    LegacyDiscoveryDataMigration.copy(
        jdbc, "FILE_METADATA", "FILE_NAME,MEDIA_TYPE,FILE_SIZE,SRC_DIR,CREATED_DATE");
    LegacyDiscoveryDataMigration.copy(
        jdbc,
        "MEDIA_LOG",
        "FILE_NAME,FILE_TYPE,FILE_DIR,FILE_SIZE,VALIDATION_STATUS,CHECK_FAILED,EXPECTED_VALUE,CHECKED_AT");
  }

  private void createTable(String sql) {
    jdbc.execute(
        "DECLARE BEGIN EXECUTE IMMEDIATE '"
            + sql
            + "'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;");
  }

  @org.springframework.transaction.annotation.Transactional
  public void save(MetadataPlan plan) {
    jdbc.update(
        "INSERT INTO MMIV_TRANSFER_PLANS (PLAN_ID,OWNER_ID,PLAN_JSON,EXPIRES_AT) VALUES (?,?,?,?)",
        plan.metadataId().toString(),
        plan.ownerId(),
        json.writeValueAsString(plan),
        Timestamp.from(Instant.now().plusSeconds(86400)));
  }

  @org.springframework.transaction.annotation.Transactional
  public MetadataPlan saveNamed(MetadataPlan plan, Collection<String> occupied) {
    try {
      jdbc.update(
          "MERGE INTO MMIV_NAME_LOCKS t USING (SELECT ? FOLDER FROM dual) s ON (t.FOLDER=s.FOLDER)"
              + " WHEN NOT MATCHED THEN INSERT(FOLDER) VALUES(s.FOLDER)",
          plan.destinationId());
    } catch (org.springframework.dao.DuplicateKeyException concurrentFirstUpload) {
      /* Another transaction initialized this folder lock. */
    }
    jdbc.queryForObject(
        "SELECT FOLDER FROM MMIV_NAME_LOCKS WHERE FOLDER=? FOR UPDATE",
        String.class,
        plan.destinationId());
    var used = new HashSet<>(occupied == null ? List.<String>of() : occupied);
    used.addAll(
        jdbc.queryForList(
            "SELECT OBJECT_PATH FROM MMIV_NAME_RESERVATIONS WHERE FOLDER=?",
            String.class,
            plan.destinationId()));
    MetadataPlan named;
    try {
      named = plan.reserveAvailableName(used);
    } catch (IllegalArgumentException error) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, error.getMessage());
    }
    String path = named.objectPath();
    save(named);
    jdbc.update(
        "INSERT INTO MMIV_NAME_RESERVATIONS(FOLDER,OBJECT_PATH,PLAN_ID) VALUES (?,?,?)",
        named.destinationId(),
        path,
        named.metadataId().toString());
    return named;
  }

  public void releaseName(UUID id, String owner) {
    jdbc.update(
        "DELETE FROM MMIV_NAME_RESERVATIONS WHERE PLAN_ID=? AND EXISTS(SELECT 1 FROM"
            + " MMIV_TRANSFER_PLANS p WHERE p.PLAN_ID=? AND p.OWNER_ID=?)",
        id.toString(),
        id.toString(),
        owner);
  }

  public List<MetadataPlan> verificationPlans(String owner, UUID run) {
    return jdbc.query(
        "SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE OWNER_ID=? AND"
            + " JSON_VALUE(PLAN_JSON,'$.verificationRunId')=?",
        (rs, row) -> json.readValue(rs.getString(1), MetadataPlan.class),
        owner,
        run.toString());
  }

  @org.springframework.transaction.annotation.Transactional
  public int deleteVerificationPlans(String owner, UUID run) {
    jdbc.update(
        "DELETE FROM MMIV_NAME_RESERVATIONS WHERE PLAN_ID IN (SELECT PLAN_ID FROM"
            + " MMIV_TRANSFER_PLANS WHERE OWNER_ID=? AND"
            + " JSON_VALUE(PLAN_JSON,'$.verificationRunId')=?)",
        owner,
        run.toString());
    return jdbc.update(
        "DELETE FROM MMIV_TRANSFER_PLANS WHERE OWNER_ID=? AND"
            + " JSON_VALUE(PLAN_JSON,'$.verificationRunId')=?",
        owner,
        run.toString());
  }

  public Optional<MetadataPlan> find(UUID id, String owner) {
    return find(id, owner, true);
  }

  @org.springframework.transaction.annotation.Transactional
  public int delete(UUID id) {
    jdbc.update("DELETE FROM MMIV_NAME_RESERVATIONS WHERE PLAN_ID=?", id.toString());
    return jdbc.update("DELETE FROM MMIV_TRANSFER_PLANS WHERE PLAN_ID=?", id.toString());
  }

  @org.springframework.transaction.annotation.Transactional
  public int deleteFolder(String folder, String path) {
    if (path == null) {
      jdbc.update("DELETE FROM MMIV_NAME_RESERVATIONS WHERE FOLDER=?", folder);
      return jdbc.update(
          "DELETE FROM MMIV_TRANSFER_PLANS WHERE JSON_VALUE(PLAN_JSON,'$.destinationId')=?",
          folder);
    }
    String prefix = path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%";
    jdbc.update(
        "DELETE FROM MMIV_NAME_RESERVATIONS WHERE FOLDER=? AND OBJECT_PATH LIKE ? ESCAPE '\\'",
        folder,
        prefix);
    return jdbc.update(
        "DELETE FROM MMIV_TRANSFER_PLANS WHERE JSON_VALUE(PLAN_JSON,'$.destinationId')=? AND"
            + " JSON_VALUE(PLAN_JSON,'$.objectPath') LIKE ? ESCAPE '\\'",
        folder,
        prefix);
  }

  public List<String> ownedKeys(String owner, List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    var args = new ArrayList<Object>();
    args.add(owner);
    ids.forEach(id -> args.add(id.toString()));
    return jdbc.query(
        "SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE OWNER_ID=? AND PLAN_ID IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ")",
        (rs, row) -> json.readValue(rs.getString(1), MetadataPlan.class).destinationKey(),
        args.toArray());
  }

  public Optional<MetadataPlan> findAny(UUID id) {
    return jdbc
        .query(
            "SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE PLAN_ID=?",
            (rs, row) -> json.readValue(rs.getString(1), MetadataPlan.class),
            id.toString())
        .stream()
        .findFirst();
  }

  public List<MetadataPlan> folderPlans(String folder, int page) {
    return jdbc.query(
        "SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE JSON_VALUE(PLAN_JSON,'$.destinationId')=?"
            + " ORDER BY EXPIRES_AT DESC,PLAN_ID OFFSET ? ROWS FETCH NEXT 100 ROWS ONLY",
        (rs, row) -> json.readValue(rs.getString(1), MetadataPlan.class),
        folder,
        page * 100);
  }

  public Optional<MetadataPlan> find(UUID id, String owner, boolean activeOnly) {
    if (!activeOnly)
      return jdbc
          .query(
              "SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE PLAN_ID=? AND OWNER_ID=?",
              (rs, row) -> json.readValue(rs.getString(1), MetadataPlan.class),
              id.toString(),
              owner)
          .stream()
          .findFirst();
    return jdbc
        .query(
            "SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE PLAN_ID=? AND OWNER_ID=? AND"
                + " EXPIRES_AT>?",
            (rs, row) -> json.readValue(rs.getString(1), MetadataPlan.class),
            id.toString(),
            owner,
            Timestamp.from(Instant.now()))
        .stream()
        .findFirst();
  }
}

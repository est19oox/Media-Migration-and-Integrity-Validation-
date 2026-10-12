package mmiv.integration;

import java.util.*;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.InitializingBean;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class MetadataPlanRepository implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    public MetadataPlanRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void afterPropertiesSet() {
        createTable("CREATE TABLE FILE_METADATA (FILE_ID NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, FILE_NAME VARCHAR2(255) NOT NULL, MEDIA_TYPE VARCHAR2(100), FILE_SIZE NUMBER, SRC_DIR VARCHAR2(2000), CREATED_DATE TIMESTAMP, CONSTRAINT MMIV_FILE_LOCATION_UNIQUE UNIQUE(FILE_NAME,SRC_DIR))");
        createTable("CREATE TABLE MEDIA_LOG (LOG_ID NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, FILE_NAME VARCHAR2(255), FILE_TYPE VARCHAR2(100), FILE_DIR VARCHAR2(2000), FILE_SIZE NUMBER, VALIDATION_STATUS VARCHAR2(20), CHECK_FAILED VARCHAR2(255), EXPECTED_VALUE VARCHAR2(2000), CHECKED_AT TIMESTAMP)");
        // Only ignore Oracle's table-already-exists error. Other schema errors abort startup.
        jdbc.execute("""
            DECLARE BEGIN
              EXECUTE IMMEDIATE 'CREATE TABLE MMIV_TRANSFER_PLANS (
                PLAN_ID VARCHAR2(36) PRIMARY KEY, OWNER_ID VARCHAR2(128) NOT NULL,
                PLAN_JSON CLOB NOT NULL, EXPIRES_AT TIMESTAMP NOT NULL)';
            EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
            """);
    }
    private void createTable(String sql) { jdbc.execute("DECLARE BEGIN EXECUTE IMMEDIATE '"+sql+"'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;"); }
    public void save(MetadataPlan plan) {
        jdbc.update("INSERT INTO MMIV_TRANSFER_PLANS (PLAN_ID,OWNER_ID,PLAN_JSON,EXPIRES_AT) VALUES (?,?,?,?)",
                plan.metadataId().toString(), plan.ownerId(), json.writeValueAsString(plan),
                Timestamp.from(Instant.now().plusSeconds(86400)));
    }
    public Optional<MetadataPlan> find(UUID id, String owner) {return find(id,owner,true);}
    public Optional<MetadataPlan> find(UUID id,String owner,boolean activeOnly) {
        if(!activeOnly) return jdbc.query("SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE PLAN_ID=? AND OWNER_ID=?",
                (rs,row)->json.readValue(rs.getString(1),MetadataPlan.class),id.toString(),owner).stream().findFirst();
        return jdbc.query("SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE PLAN_ID=? AND OWNER_ID=? AND EXPIRES_AT>?",
                (rs, row) -> json.readValue(rs.getString(1), MetadataPlan.class), id.toString(), owner,
                Timestamp.from(Instant.now())).stream().findFirst();
    }
}

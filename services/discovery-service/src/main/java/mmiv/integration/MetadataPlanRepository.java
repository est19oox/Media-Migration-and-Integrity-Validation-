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
        createTable("CREATE TABLE MMIV_VERIFICATION_RUNS (OWNER_ID VARCHAR2(128) NOT NULL, RUN_ID VARCHAR2(36) NOT NULL, CLOSED NUMBER DEFAULT 0 NOT NULL, PRIMARY KEY(OWNER_ID,RUN_ID))");
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
    @org.springframework.transaction.annotation.Transactional
    public void save(MetadataPlan plan) {
        if (plan.verificationRunId()!=null) {
            ensureRun(plan.ownerId(),plan.verificationRunId());
            int closed=jdbc.queryForObject("SELECT CLOSED FROM MMIV_VERIFICATION_RUNS WHERE OWNER_ID=? AND RUN_ID=? FOR UPDATE",Integer.class,plan.ownerId(),plan.verificationRunId().toString());
            if(closed!=0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Verification run is closed");
        }
        jdbc.update("INSERT INTO MMIV_TRANSFER_PLANS (PLAN_ID,OWNER_ID,PLAN_JSON,EXPIRES_AT) VALUES (?,?,?,?)",
                plan.metadataId().toString(), plan.ownerId(), json.writeValueAsString(plan),
                Timestamp.from(Instant.now().plusSeconds(86400)));
    }
    private void ensureRun(String owner,UUID run) {
        jdbc.update("MERGE INTO MMIV_VERIFICATION_RUNS t USING (SELECT ? OWNER_ID, ? RUN_ID FROM dual) s ON (t.OWNER_ID=s.OWNER_ID AND t.RUN_ID=s.RUN_ID) WHEN NOT MATCHED THEN INSERT (OWNER_ID,RUN_ID,CLOSED) VALUES (s.OWNER_ID,s.RUN_ID,0)",owner,run.toString());
    }
    @org.springframework.transaction.annotation.Transactional
    public void closeRun(String owner,UUID run) {
        ensureRun(owner,run);
        jdbc.queryForObject("SELECT CLOSED FROM MMIV_VERIFICATION_RUNS WHERE OWNER_ID=? AND RUN_ID=? FOR UPDATE",Integer.class,owner,run.toString());
        jdbc.update("UPDATE MMIV_VERIFICATION_RUNS SET CLOSED=1 WHERE OWNER_ID=? AND RUN_ID=?",owner,run.toString());
    }
    public boolean isClosed(String owner,UUID run) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM MMIV_VERIFICATION_RUNS WHERE OWNER_ID=? AND RUN_ID=? AND CLOSED=1",Integer.class,owner,run.toString())!=0;
    }
    public List<MetadataPlan> verificationPlans(String owner,UUID run) {
        return jdbc.query("SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE OWNER_ID=? AND JSON_VALUE(PLAN_JSON,'$.verificationRunId')=?",
            (rs,row)->json.readValue(rs.getString(1),MetadataPlan.class),owner,run.toString());
    }
    public int deleteVerificationPlans(String owner,UUID run) {
        return jdbc.update("DELETE FROM MMIV_TRANSFER_PLANS WHERE OWNER_ID=? AND JSON_VALUE(PLAN_JSON,'$.verificationRunId')=?",owner,run.toString());
    }
    public Optional<MetadataPlan> find(UUID id, String owner) {return find(id,owner,true);}
    public Optional<MetadataPlan> findAny(UUID id) {
        return jdbc.query("SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE PLAN_ID=?", (rs,row)->json.readValue(rs.getString(1),MetadataPlan.class),id.toString()).stream().findFirst();
    }
    public List<MetadataPlan> folderPlans(String folder,int page) {
        return jdbc.query("SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE JSON_VALUE(PLAN_JSON,'$.destinationId')=? ORDER BY EXPIRES_AT DESC,PLAN_ID OFFSET ? ROWS FETCH NEXT 100 ROWS ONLY", (rs,row)->json.readValue(rs.getString(1),MetadataPlan.class),folder,page*100);
    }
    public Optional<MetadataPlan> find(UUID id,String owner,boolean activeOnly) {
        if(!activeOnly) return jdbc.query("SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE PLAN_ID=? AND OWNER_ID=?",
                (rs,row)->json.readValue(rs.getString(1),MetadataPlan.class),id.toString(),owner).stream().findFirst();
        return jdbc.query("SELECT PLAN_JSON FROM MMIV_TRANSFER_PLANS WHERE PLAN_ID=? AND OWNER_ID=? AND EXPIRES_AT>?",
                (rs, row) -> json.readValue(rs.getString(1), MetadataPlan.class), id.toString(), owner,
                Timestamp.from(Instant.now())).stream().findFirst();
    }
}

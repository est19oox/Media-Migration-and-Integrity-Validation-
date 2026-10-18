package mmiv.repository;

import java.util.UUID;
import mmiv.model.VerificationRun;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VerificationRunRepository implements InitializingBean {
  private final JdbcTemplate jdbc;

  public VerificationRunRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void afterPropertiesSet() {
    jdbc.execute(
        "DECLARE BEGIN EXECUTE IMMEDIATE 'CREATE TABLE MMIV_VERIFICATION_RUNS ("
            + "OWNER_ID VARCHAR2(128) NOT NULL, RUN_ID VARCHAR2(36) NOT NULL,"
            + " CLOSED NUMBER DEFAULT 0 NOT NULL, PRIMARY KEY(OWNER_ID,RUN_ID))';"
            + " EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;");
    mmiv.integration.LegacyDiscoveryDataMigration.copy(
        jdbc, mmiv.integration.LegacyDiscoveryDataMigration.Table.MMIV_VERIFICATION_RUNS);
  }

  public VerificationRun lock(String owner, UUID run) {
    jdbc.update(
        "MERGE INTO MMIV_VERIFICATION_RUNS t USING (SELECT ? OWNER_ID, ? RUN_ID FROM dual) s ON (t.OWNER_ID=s.OWNER_ID AND t.RUN_ID=s.RUN_ID) WHEN NOT MATCHED THEN INSERT (OWNER_ID,RUN_ID,CLOSED) VALUES (s.OWNER_ID,s.RUN_ID,0)",
        owner,
        run.toString());
    int closed =
        jdbc.queryForObject(
            "SELECT CLOSED FROM MMIV_VERIFICATION_RUNS WHERE OWNER_ID=? AND RUN_ID=? FOR UPDATE",
            Integer.class,
            owner,
            run.toString());
    return new VerificationRun(owner, run, closed != 0);
  }

  @Transactional
  public void close(String owner, UUID run) {
    save(lock(owner, run).close());
  }

  private void save(VerificationRun run) {
    jdbc.update(
        "UPDATE MMIV_VERIFICATION_RUNS SET CLOSED=? WHERE OWNER_ID=? AND RUN_ID=?",
        run.closed() ? 1 : 0,
        run.ownerId(),
        run.runId().toString());
  }

  public boolean isClosed(String owner, UUID run) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM MMIV_VERIFICATION_RUNS WHERE OWNER_ID=? AND RUN_ID=? AND CLOSED=1",
            Integer.class,
            owner,
            run.toString())
        != 0;
  }
}

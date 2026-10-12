package mmiv.user_access_service.repository;

import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class OracleFolderRepository implements FolderRepository, InitializingBean {
  private final JdbcTemplate jdbc;
  public OracleFolderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  public void afterPropertiesSet() {
    jdbc.execute("DECLARE BEGIN EXECUTE IMMEDIATE 'CREATE TABLE MMIV_FOLDERS (NAME VARCHAR2(80) PRIMARY KEY, CREATED_AT TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL)'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;");
  }
  public List<String> list() { return jdbc.queryForList("SELECT NAME FROM MMIV_FOLDERS ORDER BY NAME", String.class); }
  public boolean exists(String name) { return jdbc.queryForObject("SELECT COUNT(*) FROM MMIV_FOLDERS WHERE NAME=?", Integer.class, name) != 0; }
  public void create(String name) {
    if (!FolderRepository.valid(name)) throw new IllegalArgumentException("Invalid folder name");
    jdbc.update("MERGE INTO MMIV_FOLDERS t USING (SELECT ? NAME FROM dual) s ON (t.NAME=s.NAME) WHEN NOT MATCHED THEN INSERT (NAME) VALUES (s.NAME)", name);
  }
}

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
  public List<String> list() { return jdbc.queryForList("SELECT NAME FROM MMIV_FOLDERS ORDER BY NAME", String.class).stream().filter(n->!FolderRepository.verification(n)).toList(); }
  public boolean lock(String name){return !jdbc.queryForList("SELECT NAME FROM MMIV_FOLDERS WHERE NAME=? FOR UPDATE",String.class,name).isEmpty();}
  @org.springframework.transaction.annotation.Transactional public void delete(String name){if(!lock(name))return;jdbc.update("DELETE FROM USER_DESTINATION_ACCESS WHERE DESTINATION=?",name);jdbc.update("DELETE FROM MMIV_FOLDERS WHERE NAME=?",name);}
  @org.springframework.transaction.annotation.Transactional public boolean deleteVerification(String name,String owner){
    if(!lock(name))return true;
    if(jdbc.queryForObject("SELECT COUNT(*) FROM USER_DESTINATION_ACCESS WHERE DESTINATION=? AND USER_ID<>?",Integer.class,name,owner)!=0)return false;
    if(jdbc.queryForObject("SELECT COUNT(*) FROM USER_DESTINATION_ACCESS WHERE DESTINATION=? AND USER_ID=? AND PERMISSION='WRITE'",Integer.class,name,owner)!=1)return false;
    delete(name);return true;
  }
  public boolean exists(String name) { return jdbc.queryForObject("SELECT COUNT(*) FROM MMIV_FOLDERS WHERE NAME=?", Integer.class, name) != 0; }
  public void create(String name) {
    if (!FolderRepository.valid(name)) throw new IllegalArgumentException("Invalid folder name");
    jdbc.update("MERGE INTO MMIV_FOLDERS t USING (SELECT ? NAME FROM dual) s ON (t.NAME=s.NAME) WHEN NOT MATCHED THEN INSERT (NAME) VALUES (s.NAME)", name);
  }
}

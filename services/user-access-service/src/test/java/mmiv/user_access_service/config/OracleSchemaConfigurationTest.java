package mmiv.user_access_service.config;

import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OracleSchemaConfigurationTest {
  @Test void executesOnlyTheNinePlSqlBlocksOnEachStartup() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    var schema = new OracleSchemaConfiguration().userAccessSchema(jdbc);
    schema.afterPropertiesSet();
    schema.afterPropertiesSet();
    verify(jdbc, times(18)).execute(argThat((String sql) -> sql.startsWith("DECLARE") && sql.endsWith("END;")));
  }
}

package mmiv.user_access_service.config;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Run the supplied idempotent PL/SQL schema blocks before the application becomes ready. */
@Configuration
@Profile("!test")
public class OracleSchemaConfiguration {
  private static final Pattern BLOCK = Pattern.compile("(?ms)^DECLARE\\b.*?^/\\s*$");

  @Bean
  InitializingBean userAccessSchema(JdbcTemplate jdbc) {
    return () -> {
      for (String name : new String[] {"001_create_schema.sql", "002_add_password_hash.sql", "003_create_destination_access.sql"}) {
        String sql = new ClassPathResource("db/user-access/" + name).getContentAsString(StandardCharsets.UTF_8);
        var blocks = BLOCK.matcher(sql.replace("\r\n", "\n"));
        int count = 0;
        while (blocks.find()) {
          String block = blocks.group();
          jdbc.execute(block.substring(0, block.lastIndexOf('/')).strip());
          count++;
        }
        if (count == 0) throw new IllegalStateException("Missing migration blocks: " + name);
      }
    };
  }
}

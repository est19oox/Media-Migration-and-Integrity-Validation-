package mmiv.user_access_service.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.Mockito;

class UserRepositorySelectionTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(RepositoryConfiguration.class)
                    .withPropertyValues("eureka.client.enabled=false");

    @Test
    void testProfileSelectsOnlyInMemoryRepository() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=test",
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
                .run(context -> {
                    assertTrue(context.getBeansOfType(InMemoryUserRepository.class)
                            .containsKey("inMemoryUserRepository"));
                    assertTrue(context.getBeansOfType(UserRepository.class)
                            .containsKey("inMemoryUserRepository"));
                    assertFalse(context.getBeansOfType(OracleUserRepository.class)
                            .containsKey("oracleUserRepository"));
                    assertTrue(context.getBeansOfType(UserRepository.class).size() == 1);
                });
    }

    @Test
    void localProfileSelectsOnlyOracleRepository() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "spring.datasource.url=jdbc:oracle:thin:@localhost:1521/FREEPDB1")
                .run(context -> {
                    assertTrue(context.getBeansOfType(OracleUserRepository.class)
                            .containsKey("oracleUserRepository"));
                    assertTrue(context.getBeansOfType(UserRepository.class)
                            .containsKey("oracleUserRepository"));
                    assertFalse(context.getBeansOfType(InMemoryUserRepository.class)
                            .containsKey("inMemoryUserRepository"));
                    assertTrue(context.getBeansOfType(UserRepository.class).size() == 1);
                });
    }

    @Test
    void nonTestProfileFailsWhenDatasourceIsNotConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(MissingDatasourceConfiguration.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RepositoryConfiguration {

        @Bean
        @Profile("test")
        InMemoryUserRepository inMemoryUserRepository() {
            return new InMemoryUserRepository();
        }

        @Bean
        @Profile("!test")
        OracleUserRepository oracleUserRepository(JdbcTemplate jdbcTemplate) {
            return new OracleUserRepository(jdbcTemplate);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return Mockito.mock(JdbcTemplate.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MissingDatasourceConfiguration {

        @Bean
        @Profile("!test")
        OracleUserRepository oracleUserRepository(JdbcTemplate jdbcTemplate) {
            return new OracleUserRepository(jdbcTemplate);
        }
    }
}

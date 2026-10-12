package mmiv.transfer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;
import static org.assertj.core.api.Assertions.assertThat;

class NonWebSecurityContextTest {
    @Test
    void nonWebContextStartsWithoutServletSecurityBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(TransferSecurityConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
                    assertThat(context).doesNotHaveBean("transferJwtDecoder");
                });
    }
}

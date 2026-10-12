package mmiv.reporting.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mmiv.reporting")
public class ReportingProperties {

    private String exchangeName = "mmiv.events";

    private String queueName = "reporting.events";

    private String deadLetterExchange = "mmiv.events.dlx";

    private String deadLetterQueue = "reporting.events.dlq";

    private int retryAttempts = 3;

    private Duration retryInitialDelay =
            Duration.ofMillis(500);

    private Duration migrationTimeout =
            Duration.ofMinutes(30);

    public String getExchangeName() {
        return exchangeName;
    }

    public void setExchangeName(String exchangeName) {
        this.exchangeName = exchangeName;
    }

    public String getQueueName() {
        return queueName;
    }

    public void setQueueName(String queueName) {
        this.queueName = queueName;
    }

    public String getDeadLetterExchange() {
        return deadLetterExchange;
    }

    public void setDeadLetterExchange(
            String deadLetterExchange
    ) {
        this.deadLetterExchange = deadLetterExchange;
    }

    public String getDeadLetterQueue() {
        return deadLetterQueue;
    }

    public void setDeadLetterQueue(String deadLetterQueue) {
        this.deadLetterQueue = deadLetterQueue;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public void setRetryAttempts(int retryAttempts) {
        if (retryAttempts < 1) {
            throw new IllegalArgumentException(
                    "retryAttempts must be at least 1"
            );
        }
        this.retryAttempts = retryAttempts;
    }

    public Duration getRetryInitialDelay() {
        return retryInitialDelay;
    }

    public void setRetryInitialDelay(
            Duration retryInitialDelay
    ) {
        if (retryInitialDelay == null
                || retryInitialDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "retryInitialDelay must not be negative"
            );
        }
        this.retryInitialDelay = retryInitialDelay;
    }

    public Duration getMigrationTimeout() {
        return migrationTimeout;
    }

    public void setMigrationTimeout(Duration migrationTimeout) {
        if (migrationTimeout == null
                || migrationTimeout.isZero()
                || migrationTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "migrationTimeout must be positive"
            );
        }
        this.migrationTimeout = migrationTimeout;
    }
}

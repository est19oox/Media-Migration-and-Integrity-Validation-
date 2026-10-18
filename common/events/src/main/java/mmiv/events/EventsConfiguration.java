package mmiv.events;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration(
    afterName = "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration")
@EnableScheduling
public class EventsConfiguration {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(EventsConfiguration.class);

  @Bean
  public EventPublisher eventPublisher(
      RabbitTemplate rabbit,
      @Value("${mmiv.events.outbox-directory:./.mmiv-outbox}") String directory,
      @Value("${spring.application.name}") String service,
      @Value("${mmiv.events.enabled:true}") boolean enabled) {
    if (rabbit.getConnectionFactory() instanceof CachingConnectionFactory factory) {
      factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
      factory.setPublisherReturns(true);
    }
    rabbit.setMandatory(true);
    var publisher = new EventPublisher(rabbit, directory, service, enabled);
    if (enabled && LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) instanceof Logger root) {
      var appender =
          new AppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
              // Do not copy arbitrary log messages, JWTs, passwords, or exception details into
              // audit storage.
              if (event.getLoggerName().startsWith("mmiv")
                  && !event.getLoggerName().startsWith("mmiv.events"))
                try {
                  publisher.audit(
                      "APPLICATION_LOG",
                      Map.of(
                          "logger", event.getLoggerName(), "level", event.getLevel().toString()));
                } catch (RuntimeException failure) {
                  LOG.warn("Could not enqueue application log audit event");
                }
            }
          };
      appender.setName("MMIV_RABBIT_AUDIT");
      appender.setContext(root.getLoggerContext());
      appender.start();
      root.detachAppender("MMIV_RABBIT_AUDIT");
      root.addAppender(appender);
    }
    return publisher;
  }

  @Bean
  public Declarables auditTopology() {
    var exchange = new TopicExchange("mmiv.events", true, false);
    var dlx = new DirectExchange("mmiv.audit.dlx", true, false);
    var queue =
        QueueBuilder.durable("mmiv.service.logs")
            .deadLetterExchange("mmiv.audit.dlx")
            .deadLetterRoutingKey("audit.dead")
            .build();
    var dead = new Queue("mmiv.service.logs.dlq", true);
    return new Declarables(
        exchange,
        dlx,
        queue,
        dead,
        BindingBuilder.bind(queue).to(exchange).with("service.log"),
        BindingBuilder.bind(dead).to(dlx).with("audit.dead"));
  }

  @Bean
  public LifecycleAudit lifecycleAudit(EventPublisher publisher) {
    return new LifecycleAudit(publisher);
  }

  public record LifecycleAudit(EventPublisher publisher) {
    @EventListener
    public void ready(ApplicationReadyEvent event) {
      publisher.audit("SERVICE_READY", Map.of());
    }
  }
}

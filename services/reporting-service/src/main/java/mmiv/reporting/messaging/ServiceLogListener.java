package mmiv.reporting.messaging;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import org.bson.Document;
@Component
public class ServiceLogListener {
    private final MongoTemplate mongo;
    private final JsonMapper json=JsonMapper.builder().build();
    private static final Set<String> SOURCES=Set.of("discovery-service","transfer-service","integrity-service","user-access-service","api-gateway","service-registry","reporting-service");
    private final mmiv.reporting.report.VerificationCleanupGuard cleanup;
    public ServiceLogListener(MongoTemplate mongo,mmiv.reporting.report.VerificationCleanupGuard cleanup){this.mongo=mongo;this.cleanup=cleanup;}
    @RabbitListener(queues="mmiv.service.logs",ackMode="MANUAL")
    public void receive(Message message,Channel channel) throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();
        Document document;
        UUID migration;
        try {
            var body=json.readTree(message.getBody());
            String id=body.path("eventId").stringValue();UUID.fromString(id);
            if(!SOURCES.contains(body.path("sourceService").stringValue()) || !body.path("data").isObject()
                    || body.path("operation").stringValue()==null) throw new IllegalArgumentException();
            Instant.parse(body.path("occurredAt").stringValue());
            document=Document.parse(new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8));
            document.put("_id",id);
            var data=document.get("data",Document.class);
            migration=data.getString("migrationId")==null?null:UUID.fromString(data.getString("migrationId"));
        } catch(RuntimeException e) {channel.basicReject(tag,false);return;}
        try {
            if(migration!=null && cleanup.isDeleted(migration)){channel.basicAck(tag,false);return;}
            mongo.insert(document,"service_logs");
            if(migration!=null && cleanup.isDeleted(migration))cleanup.clean(migration);
            channel.basicAck(tag,false);
        }
        catch(DuplicateKeyException e) {channel.basicAck(tag,false);}
        catch(RuntimeException e) {
            try {Thread.sleep(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            channel.basicNack(tag,false,true);
        }
    }
}

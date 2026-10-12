package mmiv.transfer.integration;
import mmiv.events.EventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RabbitOutboxTest {
    @TempDir Path directory;
    @Test void brokerFailurePersistsOriginalIdAndRetryAcknowledgesDurableMessage() throws Exception {
        var rabbit=mock(RabbitTemplate.class);var publisher=new EventPublisher(rabbit,directory.toString(),"transfer-service",true);
        publisher.audit("HTTP_REQUEST",Map.of("status",200));
        var file=Files.list(directory).findFirst().orElseThrow();String original=Files.readString(file);
        doThrow(new IllegalStateException("broker down")).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        publisher.flush();assertEquals(original,Files.readString(file));
        doAnswer(call->{
            Message message=call.getArgument(2);assertEquals(MessageDeliveryMode.PERSISTENT,message.getMessageProperties().getDeliveryMode());
            CorrelationData correlation=call.getArgument(3);correlation.getFuture().complete(new CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        var restarted=new EventPublisher(rabbit,directory.toString(),"transfer-service",true);
        restarted.flush();assertFalse(Files.exists(file));
    }
    @Test void nackOrUnroutableReturnNeverDeletesOutbox() throws Exception {
        var rabbit=mock(RabbitTemplate.class);var publisher=new EventPublisher(rabbit,directory.toString(),"transfer-service",true);
        publisher.audit("SERVICE_READY",Map.of());
        doAnswer(call->{CorrelationData c=call.getArgument(3);c.getFuture().complete(new CorrelationData.Confirm(false,"nack"));return null;})
            .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        publisher.flush();assertEquals(1,Files.list(directory).count());
        doAnswer(call->{CorrelationData c=call.getArgument(3);c.setReturned(new ReturnedMessage(call.getArgument(2),312,"NO_ROUTE","mmiv.events","service.log"));
            c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
            .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        publisher.flush();assertEquals(1,Files.list(directory).count());
    }
}

package mmiv.transfer.integration;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Cleanup seals the run and waits for all previously admitted synchronous transfers. */
@Component
public class VerificationRunGuard {
    private static class State { final ReentrantReadWriteLock lock=new ReentrantReadWriteLock(true); boolean closed; }
    private final ConcurrentHashMap<String,State> states=new ConcurrentHashMap<>();
    public record Scope(Runnable release) implements AutoCloseable {public void close(){release.run();}}
    public Scope acquire(MetadataClient.Plan plan) {
        if(plan.verificationRunId()==null)return new Scope(()->{});
        var state=states.computeIfAbsent(plan.ownerId()+":"+plan.verificationRunId(),key->new State());
        state.lock.readLock().lock();
        if(state.closed){state.lock.readLock().unlock();throw new ResponseStatusException(HttpStatus.CONFLICT,"Verification run is closed");}
        return new Scope(state.lock.readLock()::unlock);
    }
    public void cleanup(String owner,UUID run,Runnable operation) {
        var state=states.computeIfAbsent(owner+":"+run,key->new State());
        state.lock.writeLock().lock();
        try{state.closed=true;operation.run();}finally{state.lock.writeLock().unlock();}
    }
}

package mmiv.resilience;

import io.github.resilience4j.circuitbreaker.*;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.web.client.RestClientException;

/** Per-process, per-dependency availability protection; never retries a request. */
public final class DependencyCircuits {
    private DependencyCircuits() {}
    private static final ConcurrentHashMap<String,CircuitBreaker> BREAKERS=new ConcurrentHashMap<>();
    private static final CircuitBreakerConfig CONFIG=CircuitBreakerConfig.custom()
            .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
            // Longer than every client deadline: large healthy transfers are not outages.
            .slowCallDurationThreshold(Duration.ofMinutes(10))
            .waitDurationInOpenState(Duration.ofSeconds(30)).permittedNumberOfCallsInHalfOpenState(2)
            .ignoreExceptions(NonAvailabilityFailure.class)
            .build();
    /** Business/client failures do not indicate that the remote service is down. */
    public static final class NonAvailabilityFailure extends RuntimeException {
        public NonAvailabilityFailure(Throwable cause){super(cause);}
    }

    public static CircuitBreaker breaker(String dependency,String endpoint) {
        return BREAKERS.computeIfAbsent(dependency+"@"+endpoint,key->{
            var circuit=CircuitBreaker.of(dependency,CONFIG);
            circuit.getEventPublisher().onStateTransition(event->org.slf4j.LoggerFactory.getLogger(DependencyCircuits.class)
                    .warn("Dependency {} circuit transition {}",dependency,event.getStateTransition()));
            return circuit;
        });
    }
    public static ClientHttpRequestInterceptor http(String dependency,String endpoint) {
        var breaker=breaker(dependency,endpoint);
        return (request,body,execution)->{
            if(!breaker.tryAcquirePermission())throw new CircuitOpenException(dependency);
            long started=System.nanoTime();
            ClientHttpResponse response;
            try {response=execution.execute(request,body);}
            catch(IOException|RuntimeException error){breaker.onError(System.nanoTime()-started,TimeUnit.NANOSECONDS,error);throw error;}
            HttpStatusCode status;
            try {status=response.getStatusCode();}
            catch(IOException|RuntimeException error){response.close();breaker.onError(System.nanoTime()-started,TimeUnit.NANOSECONDS,error);throw error;}
            if(status.is5xxServerError()||status.value()==408||status.value()==429){
                breaker.onError(System.nanoTime()-started,TimeUnit.NANOSECONDS,new IOException("Dependency unavailable"));return response;
            }
            if(status.is4xxClientError()){breaker.releasePermission();return response;}
            // Retain the permit through body consumption so read timeouts also count.
            return new ClientHttpResponse(){
                private final AtomicBoolean completed=new AtomicBoolean();
                private void failure(Throwable error){if(completed.compareAndSet(false,true))breaker.onError(System.nanoTime()-started,TimeUnit.NANOSECONDS,error);}
                public HttpStatusCode getStatusCode() throws IOException{return response.getStatusCode();}
                public String getStatusText() throws IOException{return response.getStatusText();}
                public HttpHeaders getHeaders(){return response.getHeaders();}
                public InputStream getBody() throws IOException {
                    try{return new FilterInputStream(response.getBody()){
                        public int read() throws IOException{try{return in.read();}catch(IOException error){failure(error);throw error;}}
                        public int read(byte[] bytes,int offset,int length) throws IOException{try{return in.read(bytes,offset,length);}catch(IOException error){failure(error);throw error;}}
                    };}catch(IOException|RuntimeException error){failure(error);throw error;}
                }
                public void close(){try{response.close();}finally{if(completed.compareAndSet(false,true))breaker.onSuccess(System.nanoTime()-started,TimeUnit.NANOSECONDS);}}
            };
        };
    }
    /** An open dependency becomes the same safe 503 path as a connection failure. */
    public static final class CircuitOpenException extends RestClientException {
        public CircuitOpenException(String dependency){super("Dependency temporarily unavailable: "+dependency);}
    }
}

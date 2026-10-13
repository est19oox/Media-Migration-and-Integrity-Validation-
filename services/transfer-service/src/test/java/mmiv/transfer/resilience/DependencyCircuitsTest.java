package mmiv.transfer.resilience;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import mmiv.resilience.DependencyCircuits;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.web.client.*;

class DependencyCircuitsTest {
  @Test
  void failedServiceOpensThenHealthyProbesRestoreCallsWithoutReplayingWrites() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var status = new AtomicInteger(503);
    var calls = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          byte[] body = "fixture".getBytes();
          exchange.sendResponseHeaders(status.get(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var factory =
          new JdkClientHttpRequestFactory(
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
      factory.setReadTimeout(Duration.ofSeconds(1));
      var client =
          RestClient.builder()
              .baseUrl(url)
              .requestFactory(factory)
              .requestInterceptor(DependencyCircuits.http("transfer", url))
              .build();
      for (int i = 0; i < 5; i++)
        assertThrows(
            HttpServerErrorException.class,
            () -> client.post().uri("/").body("write").retrieve().body(String.class));
      var circuit = DependencyCircuits.breaker("transfer", url);
      assertEquals(CircuitBreaker.State.OPEN, circuit.getState());
      assertThrows(
          DependencyCircuits.CircuitOpenException.class,
          () -> client.post().uri("/").body("write").retrieve().body(String.class));
      assertEquals(5, calls.get());
      assertEquals(
          CircuitBreaker.State.CLOSED, DependencyCircuits.breaker("metadata", url).getState());
      status.set(200);
      circuit.transitionToHalfOpenState();
      assertEquals("fixture", client.get().uri("/").retrieve().body(String.class));
      assertEquals("fixture", client.get().uri("/").retrieve().body(String.class));
      assertEquals(CircuitBreaker.State.CLOSED, circuit.getState());
      assertEquals(7, calls.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void permissionDenialsAndMissingResourcesNeverOpenTheCircuit() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var status = new AtomicInteger(403);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var client =
          RestClient.builder()
              .baseUrl(url)
              .requestInterceptor(DependencyCircuits.http("user-access", url))
              .build();
      for (int code : new int[] {400, 401, 403, 404}) {
        status.set(code);
        for (int i = 0; i < 5; i++)
          assertThrows(
              HttpClientErrorException.class,
              () -> client.get().uri("/").retrieve().toBodilessEntity());
      }
      var circuit = DependencyCircuits.breaker("user-access", url);
      assertEquals(CircuitBreaker.State.CLOSED, circuit.getState());
      assertEquals(0, circuit.getMetrics().getNumberOfBufferedCalls());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void bodyReadTimeoutCountsAsFailureEvenAfterSuccessfulHeaders() throws Exception {
    String endpoint = "test:" + java.util.UUID.randomUUID();
    var interceptor = DependencyCircuits.http("metadata", endpoint);
    var execution = mock(ClientHttpRequestExecution.class);
    when(execution.execute(any(), any()))
        .thenAnswer(
            call -> {
              var response = mock(ClientHttpResponse.class);
              when(response.getStatusCode()).thenReturn(HttpStatus.OK);
              when(response.getBody())
                  .thenReturn(
                      new InputStream() {
                        public int read() throws IOException {
                          throw new java.net.SocketTimeoutException("fixture");
                        }
                      });
              return response;
            });
    for (int i = 0; i < 5; i++) {
      var response = interceptor.intercept(mock(HttpRequest.class), new byte[0], execution);
      assertThrows(java.net.SocketTimeoutException.class, () -> response.getBody().read());
      response.close();
    }
    assertEquals(
        CircuitBreaker.State.OPEN, DependencyCircuits.breaker("metadata", endpoint).getState());
    assertThrows(
        DependencyCircuits.CircuitOpenException.class,
        () -> interceptor.intercept(mock(HttpRequest.class), new byte[0], execution));
    verify(execution, times(5)).execute(any(), any());
  }

  @Test
  void responseHeaderTimeoutIsBoundedAndOpensTheCircuit() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    var calls = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          try {
            Thread.sleep(400);
            exchange.sendResponseHeaders(200, -1);
          } catch (Exception ignored) {
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var factory =
          new JdkClientHttpRequestFactory(
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
      factory.setReadTimeout(Duration.ofMillis(150));
      var client =
          RestClient.builder()
              .baseUrl(url)
              .requestFactory(factory)
              .requestInterceptor(DependencyCircuits.http("user-access", url))
              .build();
      for (int i = 0; i < 5; i++)
        assertThrows(
            ResourceAccessException.class,
            () -> client.get().uri("/").retrieve().toBodilessEntity());
      assertEquals(
          CircuitBreaker.State.OPEN, DependencyCircuits.breaker("user-access", url).getState());
      assertThrows(
          DependencyCircuits.CircuitOpenException.class,
          () -> client.get().uri("/").retrieve().toBodilessEntity());
      assertEquals(5, calls.get());
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}

package mmiv.gateway.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Atomic, shared fixed-window counters. Redis expiry bounds stored identities. */
@Component
public class RedisRequestLimiter {
  private static final RedisScript<Long> INCREMENT =
      RedisScript.of(
          """
      local count = redis.call('INCR', KEYS[1])
      if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
      return count
      """,
          Long.class);
  private final ReactiveStringRedisTemplate redis;

  public RedisRequestLimiter(ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  public Mono<Long> count(String policy, String identity, int seconds) {
    String key = "mmiv:rate:" + policy + ":" + digest(identity);
    return redis.execute(INCREMENT, List.of(key), List.of(Integer.toString(seconds))).single();
  }

  private static String digest(String identity) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}

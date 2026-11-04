package com.mygateapp.common.web;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Fixed-window counters in Redis, shared by all application instances. */
@Component
public class RateLimiter {

    private static final RedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c", Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Counts one hit and returns true while the bucket is within {@code limit} for the current window. */
    public boolean tryAcquire(String bucket, int limit, Duration window) {
        return hit(bucket, window) <= limit;
    }

    /** Counts one hit and returns the number of hits in the current window. */
    public long hit(String bucket, Duration window) {
        long windowMillis = window.toMillis();
        long windowId = clock.millis() / windowMillis;
        String key = "rl:" + bucket + ":" + windowId;
        Long count = redis.execute(INCREMENT, List.of(key), String.valueOf(windowMillis + 1000));
        return count == null ? 0 : count;
    }
}

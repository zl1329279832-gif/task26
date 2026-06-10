package com.factory.repair.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLockUtil {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ThreadLocal<String> LOCK_VALUE = new ThreadLocal<>();

    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        String value = UUID.randomUUID().toString();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, timeout, unit);
        if (Boolean.TRUE.equals(success)) {
            LOCK_VALUE.set(value);
            return true;
        }
        return false;
    }

    public void unlock(String key) {
        String value = LOCK_VALUE.get();
        if (value == null) {
            return;
        }
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
            stringRedisTemplate.execute(script, Collections.singletonList(key), value);
        } finally {
            LOCK_VALUE.remove();
        }
    }
}

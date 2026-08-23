package com.vercel.build_server.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.net.URI;

@Slf4j
@Service
public class RedisLogPublisher {

    private final String channel;
    private JedisPool jedisPool;

    public RedisLogPublisher(
            @Value("${redis.url:${REDIS_URL:redis://localhost:6379}}") String redisUrl,
            @Value("${PROJECT_ID:${project.id:}}") String projectId
    ) {
        String resolvedId = (projectId != null && !projectId.isBlank()) ? projectId : "default";
        this.channel = "logs:" + resolvedId;

        try {
            log.info("Connecting to Redis on {} for channel: {}", redisUrl, this.channel);
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            this.jedisPool = new JedisPool(poolConfig, URI.create(redisUrl));
        } catch (Exception e) {
            log.warn("Redis initialization warning: {}. Logs will output to console only.", e.getMessage());
        }
    }

    public void log(String message) {
        // Output to container stdout
        System.out.println(message);

        // Stream real-time to Redis Pub/Sub
        if (jedisPool != null) {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(channel, message);
            } catch (Exception e) {
                // Redis publish error - non fatal
            }
        }
    }

    public String getChannel() {
        return channel;
    }

    @PreDestroy
    public void cleanup() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (Exception ignored) {
            }
        }
    }
}

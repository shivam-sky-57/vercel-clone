package com.vercel.apiserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogSubscriberService implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;

    // Buffer latest logs per project for initial client load / replay
    private final Map<String, List<String>> projectLogsBuffer = new ConcurrentHashMap<>();

    // Registered SSE emitters per project
    private final Map<String, List<SseEmitter>> sseEmitters = new ConcurrentHashMap<>();

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String logBody = new String(message.getBody(), StandardCharsets.UTF_8);

        // Channel format: logs:<projectId>
        String projectId = channel.startsWith("logs:") ? channel.substring(5) : channel;

        log.info("[REDIS-LOG] [{}] -> {}", projectId, logBody);

        // 1. Buffer log
        projectLogsBuffer.computeIfAbsent(projectId, k -> new CopyOnWriteArrayList<>()).add(logBody);

        // 2. Broadcast via WebSocket STOMP topic: /topic/logs/<projectId>
        Map<String, Object> payload = Map.of(
                "projectId", projectId,
                "log", logBody,
                "timestamp", System.currentTimeMillis()
        );
        messagingTemplate.convertAndSend("/topic/logs/" + projectId, (Object) payload);

        // 3. Broadcast to active SSE Emitters
        List<SseEmitter> emitters = sseEmitters.get(projectId);
        if (emitters != null && !emitters.isEmpty()) {
            List<SseEmitter> deadEmitters = new ArrayList<>();
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("log")
                            .data(logBody));
                } catch (IOException | IllegalStateException e) {
                    deadEmitters.add(emitter);
                }
            }
            emitters.removeAll(deadEmitters);
        }
    }

    public List<String> getBufferedLogs(String projectId) {
        return projectLogsBuffer.getOrDefault(projectId, Collections.emptyList());
    }

    public SseEmitter registerSseEmitter(String projectId) {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L); // 10 minutes timeout

        sseEmitters.computeIfAbsent(projectId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(projectId, emitter));
        emitter.onTimeout(() -> removeEmitter(projectId, emitter));
        emitter.onError(e -> removeEmitter(projectId, emitter));

        // Replay existing logs to newly connected client
        List<String> existingLogs = projectLogsBuffer.get(projectId);
        if (existingLogs != null) {
            for (String logLine : existingLogs) {
                try {
                    emitter.send(SseEmitter.event().name("log").data(logLine));
                } catch (Exception ignored) {
                }
            }
        }

        return emitter;
    }

    private void removeEmitter(String projectId, SseEmitter emitter) {
        List<SseEmitter> list = sseEmitters.get(projectId);
        if (list != null) {
            list.remove(emitter);
        }
    }
}

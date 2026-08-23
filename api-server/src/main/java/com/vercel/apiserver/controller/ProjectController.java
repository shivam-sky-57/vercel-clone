package com.vercel.apiserver.controller;

import com.vercel.apiserver.dto.DeployRequest;
import com.vercel.apiserver.dto.DeployResponse;
import com.vercel.apiserver.service.EcsTaskService;
import com.vercel.apiserver.service.LogSubscriberService;
import com.vercel.apiserver.util.SlugGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProjectController {

    private final EcsTaskService ecsTaskService;
    private final LogSubscriberService logSubscriberService;

    @Value("${app.proxy.base-url:http://%s.localhost:8000}")
    private String proxyBaseUrlTemplate;

    @PostMapping("/project")
    public ResponseEntity<?> deployProject(@RequestBody DeployRequest request) {
        if (request.getGitUrl() == null || request.getGitUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "gitUrl is required"));
        }

        String projectSlug = (request.getSlug() != null && !request.getSlug().isBlank())
                ? request.getSlug().trim().toLowerCase().replaceAll("[^a-z0-9-]", "-")
                : SlugGenerator.generateSlug();

        String deploymentUrl = String.format(proxyBaseUrlTemplate, projectSlug);

        log.info("Received Deployment Request: Git=[{}] -> Slug=[{}]", request.getGitUrl(), projectSlug);

        // 1. Trigger AWS ECS Task / Build Worker
        ecsTaskService.runBuildTask(projectSlug, request.getGitUrl());

        // 2. Return Queued Response with WebSocket topic and SSE stream URL
        DeployResponse response = DeployResponse.builder()
                .status("queued")
                .data(DeployResponse.DeploymentData.builder()
                        .projectSlug(projectSlug)
                        .url(deploymentUrl)
                        .wsTopic("/topic/logs/" + projectSlug)
                        .sseStreamUrl("/project/" + projectSlug + "/logs/stream")
                        .build())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/project/{projectId}/logs")
    public ResponseEntity<List<String>> getBufferedLogs(@PathVariable String projectId) {
        return ResponseEntity.ok(logSubscriberService.getBufferedLogs(projectId));
    }

    @GetMapping(value = "/project/{projectId}/logs/stream", produces = "text/event-stream")
    public SseEmitter streamLogs(@PathVariable String projectId) {
        log.info("Client subscribed to SSE log stream for project: {}", projectId);
        return logSubscriberService.registerSseEmitter(projectId);
    }
}

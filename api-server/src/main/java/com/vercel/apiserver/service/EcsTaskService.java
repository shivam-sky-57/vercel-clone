package com.vercel.apiserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EcsTaskService {

    private final EcsClient ecsClient;

    @Value("${aws.ecs.cluster:vercel-cluster}")
    private String cluster;

    @Value("${aws.ecs.task-definition:vercel-build-task}")
    private String taskDefinition;

    @Value("${aws.ecs.container-name:vercel-builder}")
    private String containerName;

    @Value("${aws.ecs.subnets:}")
    private String subnets;

    @Value("${aws.ecs.security-groups:}")
    private String securityGroups;

    @Value("${s3.bucket.name:shivam-vercel-pro}")
    private String s3BucketName;

    @Value("${aws.region:ap-south-1}")
    private String awsRegion;

    @Value("${aws.access-key-id:}")
    private String accessKey;

    @Value("${aws.secret-access-key:}")
    private String secretKey;

    @Value("${spring.data.redis.url:redis://localhost:6379}")
    private String redisUrl;

    public void runBuildTask(String projectId, String gitUrl) {
        log.info("Triggering AWS ECS Fargate Task for Project: {} | Git Repo: {}", projectId, gitUrl);

        try {
            List<KeyValuePair> envVars = new ArrayList<>(List.of(
                    KeyValuePair.builder().name("GIT_REPOSITORY__URL").value(gitUrl).build(),
                    KeyValuePair.builder().name("PROJECT_ID").value(projectId).build(),
                    KeyValuePair.builder().name("AWS_REGION").value(awsRegion).build(),
                    KeyValuePair.builder().name("S3_BUCKET_NAME").value(s3BucketName).build(),
                    KeyValuePair.builder().name("REDIS_URL").value(redisUrl).build()
            ));

            if (accessKey != null && !accessKey.isBlank()) {
                envVars.add(KeyValuePair.builder().name("AWS_ACCESS_KEY_ID").value(accessKey).build());
            }
            if (secretKey != null && !secretKey.isBlank()) {
                envVars.add(KeyValuePair.builder().name("AWS_SECRET_ACCESS_KEY").value(secretKey).build());
            }

            ContainerOverride containerOverride = ContainerOverride.builder()
                    .name(containerName)
                    .environment(envVars)
                    .build();

            TaskOverride taskOverride = TaskOverride.builder()
                    .containerOverrides(containerOverride)
                    .build();

            RunTaskRequest.Builder requestBuilder = RunTaskRequest.builder()
                    .cluster(cluster)
                    .taskDefinition(taskDefinition)
                    .launchType(LaunchType.FARGATE)
                    .count(1)
                    .overrides(taskOverride);

            // Configure VPC networking if subnets are provided
            if (subnets != null && !subnets.isBlank()) {
                List<String> subnetList = Arrays.stream(subnets.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();

                List<String> sgList = (securityGroups != null && !securityGroups.isBlank())
                        ? Arrays.stream(securityGroups.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                        : List.of();

                AwsVpcConfiguration vpcConfig = AwsVpcConfiguration.builder()
                        .subnets(subnetList)
                        .securityGroups(sgList)
                        .assignPublicIp(AssignPublicIp.ENABLED)
                        .build();

                requestBuilder.networkConfiguration(
                        NetworkConfiguration.builder().awsvpcConfiguration(vpcConfig).build()
                );
            }

            RunTaskResponse response = ecsClient.runTask(requestBuilder.build());
            log.info("AWS ECS Task provisioned successfully! Tasks started: {}", response.tasks().size());

        } catch (Exception e) {
            log.warn("AWS ECS RunTask skipped: {}. Falling back to local Docker build worker.", e.getMessage());
            // Fallback for local testing if ECS cluster is not yet created on AWS
            runLocalDockerFallback(projectId, gitUrl);
        }
    }

    private void runLocalDockerFallback(String projectId, String gitUrl) {
        log.info("Attempting local Docker runner fallback for Project: {}", projectId);
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            
            // In Docker on Windows/Mac, containers reach the host via host.docker.internal
            String containerRedisUrl = redisUrl.contains("localhost") 
                    ? redisUrl.replace("localhost", "host.docker.internal")
                    : (redisUrl.contains("127.0.0.1") ? redisUrl.replace("127.0.0.1", "host.docker.internal") : redisUrl);

            String accessKeyEnv = (accessKey != null && !accessKey.isBlank()) 
                    ? String.format("-e AWS_ACCESS_KEY_ID=\"%s\" ", accessKey) 
                    : (System.getenv("AWS_ACCESS_KEY_ID") != null ? String.format("-e AWS_ACCESS_KEY_ID=\"%s\" ", System.getenv("AWS_ACCESS_KEY_ID")) : "");

            String secretKeyEnv = (secretKey != null && !secretKey.isBlank()) 
                    ? String.format("-e AWS_SECRET_ACCESS_KEY=\"%s\" ", secretKey) 
                    : (System.getenv("AWS_SECRET_ACCESS_KEY") != null ? String.format("-e AWS_SECRET_ACCESS_KEY=\"%s\" ", System.getenv("AWS_SECRET_ACCESS_KEY")) : "");

            String command = String.format(
                    "docker run --rm -d -e PROJECT_ID=\"%s\" -e GIT_REPOSITORY__URL=\"%s\" -e AWS_REGION=\"%s\" -e S3_BUCKET_NAME=\"%s\" -e REDIS_URL=\"%s\" %s%svercel-build-server",
                    projectId, gitUrl, awsRegion, s3BucketName, containerRedisUrl, accessKeyEnv, secretKeyEnv
            );

            log.info("Spawning local build worker: {}", command.replaceAll("AWS_SECRET_ACCESS_KEY=\".*?\"", "AWS_SECRET_ACCESS_KEY=\"***\""));

            ProcessBuilder pb = isWindows 
                    ? new ProcessBuilder("cmd.exe", "/c", command)
                    : new ProcessBuilder("sh", "-c", command);
            pb.start();
            log.info("Local Docker build task launched in background for project: {}", projectId);
        } catch (Exception ex) {
            log.error("Failed to run local Docker fallback: {}", ex.getMessage());
        }
    }
}

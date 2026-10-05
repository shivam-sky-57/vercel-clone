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

    @Value("${aws.ecs.cluster:${AWS_ECS_CLUSTER:vercel-cluster}}")
    private String cluster;

    @Value("${aws.ecs.task-definition:${AWS_ECS_TASK_DEFINITION:vercel-build-task}}")
    private String taskDefinition;

    @Value("${aws.ecs.container-name:${AWS_ECS_CONTAINER_NAME:vercel-builder}}")
    private String containerName;

    @Value("${aws.ecs.subnets:${AWS_ECS_SUBNETS:}}")
    private String subnets;

    @Value("${aws.ecs.security-groups:${AWS_ECS_SECURITY_GROUPS:}}")
    private String securityGroups;

    @Value("${s3.bucket.name:${S3_BUCKET_NAME:}}")
    private String s3BucketName;

    @Value("${aws.region:${AWS_REGION:ap-south-1}}")
    private String awsRegion;

    @Value("${aws.access-key-id:${AWS_ACCESS_KEY_ID:}}")
    private String accessKey;

    @Value("${aws.secret-access-key:${AWS_SECRET_ACCESS_KEY:}}")
    private String secretKey;

    @Value("${spring.data.redis.url:${REDIS_URL:redis://localhost:6379}}")
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

            String finalAccessKey = (accessKey != null && !accessKey.isBlank()) ? accessKey : System.getenv("AWS_ACCESS_KEY_ID");
            String finalSecretKey = (secretKey != null && !secretKey.isBlank()) ? secretKey : System.getenv("AWS_SECRET_ACCESS_KEY");

            if (finalAccessKey != null && !finalAccessKey.isBlank()) {
                envVars.add(KeyValuePair.builder().name("AWS_ACCESS_KEY_ID").value(finalAccessKey).build());
            }
            if (finalSecretKey != null && !finalSecretKey.isBlank()) {
                envVars.add(KeyValuePair.builder().name("AWS_SECRET_ACCESS_KEY").value(finalSecretKey).build());
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

            String finalAccessKey = (accessKey != null && !accessKey.isBlank()) ? accessKey : System.getenv("AWS_ACCESS_KEY_ID");
            String finalSecretKey = (secretKey != null && !secretKey.isBlank()) ? secretKey : System.getenv("AWS_SECRET_ACCESS_KEY");

            String accessKeyEnv = (finalAccessKey != null && !finalAccessKey.isBlank()) 
                    ? String.format("-e AWS_ACCESS_KEY_ID=\"%s\" ", finalAccessKey) 
                    : "";

            String secretKeyEnv = (finalSecretKey != null && !finalSecretKey.isBlank()) 
                    ? String.format("-e AWS_SECRET_ACCESS_KEY=\"%s\" ", finalSecretKey) 
                    : "";

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

package com.vercel.build_server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

@Slf4j
@Configuration
public class AwsS3Config {

    @Value("${aws.region:${AWS_REGION:ap-south-1}}")
    private String awsRegion;

    @Value("${aws.access-key-id:${AWS_ACCESS_KEY_ID:}}")
    private String accessKey;

    @Value("${aws.secret-access-key:${AWS_SECRET_ACCESS_KEY:}}")
    private String secretKey;

    @Value("${aws.s3.endpoint:${AWS_ENDPOINT:}}")
    private String endpointOverride;

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(awsRegion));

        String envAccessKey = System.getenv("AWS_ACCESS_KEY_ID");
        String envSecretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
        String finalAccessKey = (accessKey != null && !accessKey.isBlank()) ? accessKey : envAccessKey;
        String finalSecretKey = (secretKey != null && !secretKey.isBlank()) ? secretKey : envSecretKey;

        if (finalAccessKey != null && !finalAccessKey.isBlank() && finalSecretKey != null && !finalSecretKey.isBlank()) {
            log.info("Configuring S3 Client with explicit StaticCredentialsProvider for access key: {}***", 
                    finalAccessKey.substring(0, Math.min(6, finalAccessKey.length())));
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(finalAccessKey.trim(), finalSecretKey.trim())
            ));
        } else {
            log.info("Configuring S3 Client with DefaultCredentialsProvider (ECS Task Role / IAM / Profile)");
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        if (endpointOverride != null && !endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride))
                   .forcePathStyle(true);
        }

        return builder.build();
    }
}

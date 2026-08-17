package com.vercel.build_server.config;

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

@Configuration
public class AwsS3Config {

    @Value("${aws.region:ap-south-1}")
    private String awsRegion;

    @Value("${aws.access-key-id:}")
    private String accessKey;

    @Value("${aws.secret-access-key:}")
    private String secretKey;

    @Value("${aws.s3.endpoint:}")
    private String endpointOverride;

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(awsRegion));

        // If explicit access keys are provided, use them; otherwise use default AWS provider chain (ECS Task Role / IAM)
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)
            ));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        if (endpointOverride != null && !endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride))
                   .forcePathStyle(true);
        }

        return builder.build();
    }
}

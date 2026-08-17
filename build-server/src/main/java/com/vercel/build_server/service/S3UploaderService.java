package com.vercel.build_server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3UploaderService {

    private final S3Client s3Client;
    private final Tika tika = new Tika();

    @Value("${s3.bucket.name:${S3_BUCKET_NAME:vercel-clone-outputs}}")
    private String bucketName;

    public void uploadDirectory(String projectId, Path buildOutputDir) throws Exception {
        log.info("Starting upload of static assets to S3 bucket: {}", bucketName);

        if (!Files.exists(buildOutputDir)) {
            throw new IllegalArgumentException("Build output directory does not exist: " + buildOutputDir);
        }

        AtomicInteger fileCount = new AtomicInteger(0);

        try (var stream = Files.walk(buildOutputDir)) {
            stream.filter(Files::isRegularFile).forEach(filePath -> {
                try {
                    String relativePath = buildOutputDir.relativize(filePath).toString().replace("\\", "/");
                    String s3Key = "__outputs/" + projectId + "/" + relativePath;
                    String mimeType = determineContentType(filePath, relativePath);

                    log.info("Uploading: {} -> S3 Key: {} [{}]", relativePath, s3Key, mimeType);

                    PutObjectRequest putRequest = PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(s3Key)
                            .contentType(mimeType)
                            .build();

                    s3Client.putObject(putRequest, RequestBody.fromFile(filePath));
                    fileCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Failed uploading file: {}", filePath, e);
                    throw new RuntimeException("S3 upload failed for: " + filePath, e);
                }
            });
        }

        log.info("S3 upload complete! Total files uploaded: {}", fileCount.get());
    }

    private String determineContentType(Path path, String relativePath) {
        String lower = relativePath.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".wasm")) return "application/wasm";

        try {
            return tika.detect(path);
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }
}

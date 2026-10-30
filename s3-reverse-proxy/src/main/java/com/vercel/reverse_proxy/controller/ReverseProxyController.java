package com.vercel.reverse_proxy.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
public class ReverseProxyController {

    private final WebClient webClient;
    private final String resolvedS3BaseUrl;

    public ReverseProxyController(
            @Value("${s3.base.url:${S3_BASE_URL:}}") String s3BaseUrlConfig,
            @Value("${s3.bucket.name:${S3_BUCKET_NAME:}}") String s3BucketName,
            @Value("${aws.region:${AWS_REGION:ap-south-1}}") String awsRegion
    ) {
        String base;
        if (s3BaseUrlConfig != null && !s3BaseUrlConfig.isBlank()) {
            base = s3BaseUrlConfig.endsWith("/") ? s3BaseUrlConfig.substring(0, s3BaseUrlConfig.length() - 1) : s3BaseUrlConfig;
        } else if (s3BucketName != null && !s3BucketName.isBlank()) {
            base = String.format("https://%s.s3.%s.amazonaws.com/__outputs", s3BucketName, awsRegion);
        } else {
            base = "";
        }
        this.resolvedS3BaseUrl = base;
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
        log.info("Initialized Reverse Proxy with Target Base URL: {}", this.resolvedS3BaseUrl);
    }

    @RequestMapping("/**")
    public Mono<ResponseEntity<byte[]>> proxyRequest(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String host = request.getHeaders().getFirst(HttpHeaders.HOST);
        String path = request.getPath().pathWithinApplication().value();

        String slug = resolveSlug(request, host);

        if (slug == null || slug.isBlank() || slug.equalsIgnoreCase("localhost") || slug.equalsIgnoreCase("localtest") || slug.equalsIgnoreCase("deploy")) {
            String welcomeHtml = "<!DOCTYPE html><html><head><title>Deployment App Proxy</title>"
                    + "<style>body{background:#0a0a0a;color:#eee;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;}"
                    + ".box{background:#161616;border:1px solid #333;padding:40px;border-radius:12px;text-align:center;max-width:500px;}"
                    + "a{color:#0070f3;text-decoration:none;font-weight:bold;} a:hover{text-decoration:underline;}"
                    + "</style></head><body><div class='box'>"
                    + "<h2>Deployment App - Reverse Proxy</h2>"
                    + "<p style='color:#888;'>Access your deployed project via its unique subdomain or query parameter:</p>"
                    + "<p><code>http://&lt;project-slug&gt;.localtest.me:8000</code></p>"
                    + "</div></body></html>";
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(welcomeHtml.getBytes()));
        }

        if (resolvedS3BaseUrl == null || resolvedS3BaseUrl.isBlank()) {
            String errHtml = "<!DOCTYPE html><html><body style='font-family:sans-serif;background:#111;color:#fff;padding:40px;'>"
                    + "<h2>Deployment Storage Not Configured</h2>"
                    + "<p>Please set <code>S3_BUCKET_NAME</code> or <code>s3.bucket.name</code> in environment variables or <code>application-secret.properties</code>.</p>"
                    + "</body></html>";
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.TEXT_HTML)
                    .body(errHtml.getBytes()));
        }

        String resolvedPath = (path == null || path.equals("/") || path.isBlank()) ? "/index.html" : path;
        String targetUrl = resolvedS3BaseUrl + "/" + slug + (resolvedPath.startsWith("/") ? resolvedPath : "/" + resolvedPath);

        log.info("Proxying: Host=[{}] -> Slug=[{}] Path=[{}] -> Target=[{}]", host, slug, path, targetUrl);

        return fetchAndForward(targetUrl)
                .flatMap(response -> {
                    if (response.getStatusCode().is4xxClientError() && !resolvedPath.contains(".")) {
                        String indexPath = resolvedS3BaseUrl + "/" + slug + "/index.html";
                        log.info("SPA route detected (4xx for {}). Falling back to: {}", resolvedPath, indexPath);
                        return fetchAndForward(indexPath)
                                .map(indexRes -> ResponseEntity.status(HttpStatus.OK)
                                         .headers(indexRes.getHeaders())
                                         .body(indexRes.getBody()));
                    }
                    return Mono.just(response);
                })
                .onErrorResume(e -> {
                    log.error("Proxy error for URL {}: {}", targetUrl, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .contentType(MediaType.TEXT_PLAIN)
                            .body(("502 Bad Gateway: " + e.getMessage()).getBytes()));
                });
    }

    private Mono<ResponseEntity<byte[]>> fetchAndForward(String targetUrl) {
        return webClient.get()
                .uri(targetUrl)
                .exchangeToMono(clientResponse -> {
                    HttpStatus status = HttpStatus.valueOf(clientResponse.statusCode().value());
                    HttpHeaders headers = new HttpHeaders();
                    clientResponse.headers().asHttpHeaders().forEach((key, values) -> {
                        if (!key.equalsIgnoreCase(HttpHeaders.TRANSFER_ENCODING)) {
                            headers.put(key, values);
                        }
                    });

                    return clientResponse.bodyToMono(byte[].class)
                            .defaultIfEmpty(new byte[0])
                            .map(body -> ResponseEntity.status(status).headers(headers).body(body));
                });
    }

    private String resolveSlug(ServerHttpRequest request, String host) {
        if (host != null) {
            String hostWithoutPort = host.contains(":") ? host.split(":")[0] : host;
            String[] parts = hostWithoutPort.split("\\.");
            if (parts.length > 1 && !parts[0].equalsIgnoreCase("localhost") && !parts[0].equalsIgnoreCase("www") && !parts[0].equalsIgnoreCase("localtest") && !parts[0].equalsIgnoreCase("deploy")) {
                return parts[0];
            }
        }

        String querySlug = request.getQueryParams().getFirst("__slug");
        if (querySlug == null || querySlug.isBlank()) {
            querySlug = request.getQueryParams().getFirst("slug");
        }
        if (querySlug != null && !querySlug.isBlank()) {
            return querySlug.trim();
        }

        String headerSlug = request.getHeaders().getFirst("X-Project-Slug");
        if (headerSlug != null && !headerSlug.isBlank()) {
            return headerSlug.trim();
        }

        return null;
    }
}

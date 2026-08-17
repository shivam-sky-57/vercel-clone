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
    private final String s3BaseUrl;

    public ReverseProxyController(
            @Value("${s3.base.url:https://shivam-vercel-pro.s3.ap-south-1.amazonaws.com/__outputs}") String s3BaseUrl
    ) {
        this.s3BaseUrl = s3BaseUrl.endsWith("/") ? s3BaseUrl.substring(0, s3BaseUrl.length() - 1) : s3BaseUrl;
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
        log.info("Initialized S3 Reverse Proxy with Target Base URL: {}", this.s3BaseUrl);
    }

    @RequestMapping("/**")
    public Mono<ResponseEntity<byte[]>> proxyRequest(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String host = request.getHeaders().getFirst(HttpHeaders.HOST);
        String path = request.getPath().pathWithinApplication().value();

        String subdomain = extractSubdomain(host);
        if (subdomain == null || subdomain.isBlank() || subdomain.equalsIgnoreCase("localhost")) {
            String welcomeHtml = "<!DOCTYPE html><html><body style='font-family:sans-serif;text-align:center;padding:50px;'>"
                    + "<h1>🚀 Vercel S3 Reverse Proxy is Running!</h1>"
                    + "<p>Access your deployed website with its subdomain:</p>"
                    + "<p>👉 <a href='http://test-site-01.localhost:8000'>http://test-site-01.localhost:8000</a></p>"
                    + "</body></html>";
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(welcomeHtml.getBytes()));
        }

        // Map root / to /index.html
        String resolvedPath = (path == null || path.equals("/") || path.isBlank()) ? "/index.html" : path;
        String targetUrl = s3BaseUrl + "/" + subdomain + (resolvedPath.startsWith("/") ? resolvedPath : "/" + resolvedPath);

        log.info("Proxying: Host=[{}] -> Subdomain=[{}] Path=[{}] -> Target=[{}]", host, subdomain, path, targetUrl);

        return fetchAndForward(targetUrl)
                .flatMap(response -> {
                    // SPA Fallback: If route is 404 or 403 and is not an asset/file (no file extension like .js, .css), fallback to /index.html
                    if (response.getStatusCode().is4xxClientError() && !resolvedPath.contains(".")) {
                        String indexPath = s3BaseUrl + "/" + subdomain + "/index.html";
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

    private String extractSubdomain(String host) {
        if (host == null) return null;
        // Strip port if present (e.g., test-site-01.localhost:8000 -> test-site-01.localhost)
        String hostWithoutPort = host.contains(":") ? host.split(":")[0] : host;
        String[] parts = hostWithoutPort.split("\\.");
        if (parts.length > 1) {
            return parts[0];
        }
        return null;
    }
}

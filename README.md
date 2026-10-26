# Vercel Clone

A distributed deployment platform built with Java 21, Spring Boot, AWS S3, Docker, and Redis.

---

## Overview

This project automates the frontend deployment lifecycle: cloning source code, running production builds, uploading static assets to AWS S3, and serving them via a Spring WebFlux reverse proxy with real-time log streaming.

---

## Architecture

```
[ User / Browser ] 
        │
        ├─ 1. POST /project (Git URL + Slug) ────────────────────────► [ Spring Boot API Server ] (Port 9000)
        │                                                                     │
        │                                                                     ▼ (AWS ECS / Docker Task)
        │                                                             [ Build Worker Container ]
        │                                                                ├─ Git clone repository
        │                                                                ├─ npm install && npm run build
        │                                                                ├─ Publishes logs ──► [ Redis Pub/Sub ]
        │                                                                └─ Uploads /dist  ──► [ AWS S3 Bucket ]
        │                                                                                             │
        ├─ 2. Real-time Logs (WebSocket / SSE) ◄── [ API Server ] ◄── [ Redis Pub/Sub: logs:<slug> ]  │
        │                                                                                             │
        └─ 3. GET http://<slug>.localhost:8000 ──────────────────────► [ Spring WebFlux Reverse Proxy ] (Port 8000)
                                                                              │                       │
                                                                              └─ Streams static files ┘
```

---

## Features

- **Isolated Execution**: Builds run inside isolated Docker containers or AWS ECS tasks and clean up on completion.
- **Log Pipeline**: Process stdout and stderr are captured and published through Redis Pub/Sub to WebSockets and SSE.
- **Reverse Proxy**: Built with Spring WebFlux to route requests (`http://<slug>.localhost:8000`) directly to S3.
- **SPA Fallback**: Resolves client-side routes by falling back to `index.html` for non-asset 404/403 responses.
- **MIME Type Resolution**: Auto-detects content types for accurate static asset delivery.
- **Web Dashboard**: UI to trigger deployments, monitor live logs, and access preview URLs.

---

## Services

| Service | Stack | Description | Port |
| :--- | :--- | :--- | :--- |
| `api-server` | Spring Boot, WebSocket, Redis, AWS SDK | Handles deployment requests, log streaming, and hosts the dashboard. | `9000` |
| `build-server` | Java 21, Node.js, Git, Jedis, AWS SDK | Worker that clones repositories, runs builds, and uploads artifacts to S3. | Worker |
| `s3-reverse-proxy` | Spring WebFlux, Project Reactor, Netty | Reverse proxy routing subdomain requests to S3 objects. | `8000` |

---

## Local Setup

### Prerequisites
- Java 21 JDK
- Docker Desktop
- AWS S3 bucket and credentials (or local S3 compatible storage)
- Redis

---

### 1. Start Redis
```bash
docker run -d -p 6379:6379 --name local-redis redis:alpine
```

---

### 2. Build the Worker Container
```bash
cd build-server
docker build -t vercel-build-server .
cd ..
```

---

### 3. Start S3 Reverse Proxy
```bash
cd s3-reverse-proxy
.\mvnw.cmd spring-boot:run
```
The reverse proxy runs on `http://localhost:8000`.

---

### 4. Start API Server
```bash
cd api-server
.\mvnw.cmd spring-boot:run
```
The dashboard runs on `http://localhost:9000`.

---

## Deployment Flow

1. Open `http://localhost:9000`.
2. Enter a public Git repository URL (e.g. React/Vite project).
3. Optionally specify a custom slug.
4. Click Deploy Project.
5. Monitor logs in the terminal console.
6. Open the generated preview URL once the build completes.

---

## S3 Storage Layout

Static assets are stored with the following layout:

```
s3://<bucket-name>/
└── __outputs/
     ├── my-portfolio/
     │    ├── index.html
     │    └── assets/
     └── test-project/
          ├── index.html
          └── assets/
```

---

## Configuration

Settings can be specified in `application-secret.properties` or environment variables:

| Variable | Property Key | Description |
| :--- | :--- | :--- |
| `AWS_REGION` | `aws.region` | AWS region (default: `ap-south-1`) |
| `AWS_ACCESS_KEY_ID` | `aws.access-key-id` | AWS access key |
| `AWS_SECRET_ACCESS_KEY` | `aws.secret-access-key` | AWS secret key |
| `S3_BUCKET_NAME` | `s3.bucket.name` | Target S3 bucket name |
| `REDIS_URL` | `redis.url` | Redis host connection URL |
| `PROXY_BASE_URL` | `app.proxy.base-url` | Base URL template for preview domains |

---

## License

MIT

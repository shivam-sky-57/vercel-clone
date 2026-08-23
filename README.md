<div align="center">

# 🚀 Vercel Clone — Cloud-Native Distributed Deployment Engine

<p align="center">
  <strong>A production-ready, distributed deployment platform mimicking Vercel's architecture built with Java 21, Spring Boot, AWS (S3 & ECS Fargate), Docker, and Redis.</strong>
</p>

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4+-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)](https://spring.io/projects/spring-boot)
[![AWS](https://img.shields.io/badge/AWS-S3_%26_ECS-232F3E?style=for-the-badge&logo=amazon-aws&logoColor=white)](https://aws.amazon.com/)
[![Docker](https://img.shields.io/badge/Docker-Containerized-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)
[![Redis](https://img.shields.io/badge/Redis-Pub%2FSub-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](LICENSE)

---

</div>

## 📌 Overview

This project is a high-performance, distributed microservices platform that automates the entire frontend deployment lifecycle—from cloning source code and executing production builds to deploying static assets to **AWS S3** and serving them dynamically through a **Spring WebFlux Reactive Reverse Proxy** with real-time log streaming.

---

## 🏛️ System Architecture

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

## ⚡ Key Highlights & Features

- **🛡️ Ephemeral Sandbox Execution**: Build workers run inside isolated Docker containers (`--rm`) or AWS ECS Fargate tasks with zero cross-tenant state pollution and automatic destruction on exit.
- **📡 Real-Time Distributed Log Pipeline**: Stdout/stderr from `git` and `npm` processes are captured asynchronously and broadcasted through **Redis Pub/Sub** to **WebSockets (STOMP)** and **Server-Sent Events (SSE)**.
- **⚡ Reactive Non-Blocking Reverse Proxy**: Built using **Spring WebFlux** and Netty to route subdomain requests (`http://<slug>.localhost:8000`) directly to AWS S3 without blocking threads.
- **🔄 Single Page Application (SPA) Routing**: Intelligent fallback handling that returns `index.html` on 404/403 errors, ensuring client-side routers (React Router, Vue Router, etc.) function flawlessly.
- **📁 MIME-Type Auto-Detection**: Uses **Apache Tika** and strict file extension resolution to ensure accurate `Content-Type` headers (`application/javascript`, `text/css`, `image/svg+xml`) for strict browser compliance.
- **🎨 Built-in Dashboard UI**: Sleek, Vercel-inspired dashboard with live terminal log stream and one-click deployment preview links.

---

## 🧩 Microservices Breakdown

| Service | Technology Stack | Responsibility | Port |
| :--- | :--- | :--- | :--- |
| **`api-server`** | Spring Boot, WebSocket (STOMP), Spring Data Redis, AWS SDK v2 (ECS) | Control plane that orchestrates deployments, manages Redis Pub/Sub listeners, broadcasts logs over WebSockets, and hosts the dashboard UI. | `9000` |
| **`build-server`** | Java 21, Node.js 20 LTS, Git, Jedis, AWS SDK v2 (S3), Apache Tika | Ephemeral worker container that clones target Git repos, executes `npm install && npm run build`, and deploys compiled artifacts to S3. | *Worker* |
| **`s3-reverse-proxy`** | Spring Boot WebFlux, Project Reactor, Netty, Reactive WebClient | Data plane proxy that intercepts subdomain requests, maps them to `__outputs/<slug>/...` on S3, and streams assets back to users. | `8000` |

---

## 🚀 Quick Start & Local Setup

### 📋 Prerequisites
- **Java 21 JDK** installed
- **Docker Desktop** running
- **AWS S3 Bucket** & **IAM Credentials** (with S3 PutObject / GetObject permissions)

---

### 1️⃣ Clone the Repository
```bash
git clone https://github.com/shivam-sky-57/vercel-clone.git
cd vercel-clone
```

---

### 2️⃣ Start Local Redis (Message Broker)
```bash
docker run -d -p 6379:6379 --name local-redis redis:alpine
```

---

### 3️⃣ Build the Build Worker Docker Image
```bash
cd build-server
docker build -t vercel-build-server .
cd ..
```

---

### 4️⃣ Start the S3 Reverse Proxy (Data Plane)
In a new terminal window:
```bash
cd s3-reverse-proxy
# Optional: Set your AWS credentials
$env:AWS_REGION="ap-south-1"
$env:S3_BUCKET_NAME="your-s3-bucket-name"

.\mvnw.cmd spring-boot:run
```
> The Reverse Proxy starts on **`http://localhost:8000`**.

---

### 5️⃣ Start the API Server (Control Plane)
In another terminal window:
```bash
cd api-server
# Pass your AWS credentials and S3 bucket
$env:AWS_ACCESS_KEY_ID="your_aws_access_key"
$env:AWS_SECRET_ACCESS_KEY="your_aws_secret_key"
$env:AWS_REGION="ap-south-1"
$env:S3_BUCKET_NAME="your-s3-bucket-name"

.\mvnw.cmd spring-boot:run
```
> The API Dashboard starts on **`http://localhost:9000`**.

---

## 🎮 How to Deploy & Demo

1. Open your browser at **`http://localhost:9000`**.
2. Enter any public React / Vite repository URL (e.g., `https://github.com/shivam-sky-57/sample.git`).
3. *(Optional)* Provide a custom subdomain slug (e.g., `my-portfolio`) or leave blank for auto-generation.
4. Click **"🚀 Deploy Project"**.
5. Watch the build logs stream line-by-line in the integrated real-time terminal.
6. Once completed, click the **Live Preview URL** (e.g. `http://my-portfolio.localhost:8000`) to interact with your live site!

---

## 📁 S3 Storage Object Layout

Deployed static assets are organized on AWS S3 with clean namespace isolation:

```
s3://<your-bucket-name>/
└── __outputs/
     ├── my-portfolio/
     │    ├── index.html
     │    └── assets/
     │         ├── index-D7h.js
     │         └── index-K3.css
     └── fast-cloud-760/
          ├── index.html
          └── assets/
               └── index-xxx.js
```

---

## 🛠️ Configuration Reference

### Environment Variables

| Variable | Description | Default |
| :--- | :--- | :--- |
| `AWS_REGION` | AWS Region of your S3 Bucket & ECS Cluster | `ap-south-1` |
| `AWS_ACCESS_KEY_ID` | IAM User Access Key | *(Optional if IAM Role used)* |
| `AWS_SECRET_ACCESS_KEY` | IAM User Secret Key | *(Optional if IAM Role used)* |
| `S3_BUCKET_NAME` | Name of your AWS S3 bucket | `vercel-clone-outputs` |
| `REDIS_URL` | Redis connection URL for real-time log Pub/Sub | `redis://localhost:6379` |
| `PROXY_BASE_URL` | Base URL template for preview routing | `http://%s.localhost:8000` |

---

## 🛡️ Security Best Practices

- **Zero Hardcoded Secrets**: All credentials are dynamically supplied via environment variables or AWS IAM Task Roles.
- **Build Isolation**: Untrusted repository build scripts execute inside isolated sandbox containers without host filesystem access.
- **MIME Type Hardening**: Strict MIME headers prevent cross-site script injection and MIME-sniffing vulnerabilities.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

# AGENTS.md — EduPulse RAG System (Cloud, DevOps & Kubernetes Knowledge Assistant)

This file defines the mandatory architecture, tech stack, conventions, and boundaries for all AI coding agents and team members working on this repository. **Read this entire file before generating or modifying any code.**

---

## 1. Project Overview & Domain
* **System Purpose:** An enterprise-grade, multi-microservice Retrieval-Augmented Generation (RAG) assistant designed to maximize retrieval/answer quality while minimizing operating costs.
* **Knowledge Domain:** **Cloud, DevOps & Kubernetes** (CNCF whitepapers, Kubernetes official documentation, Docker/Kubernetes GitHub docs).
* **Deadline (Epic 1 - RAG):** December 7, 2026.

---

## 2. Mandatory Tech Stack & Versions
All modules must align with the following exact stack:
* **Language:** Java `21`
* **Framework:** Spring Boot `4.0.7`
* **Microservices / Cloud:** Spring Cloud `2025.1.2`
* **AI Framework:** Spring AI `2.0.0`
* **Database & Vector Store:** PostgreSQL with `pgvector` extension
* **Service Discovery:** Netflix Eureka Server (`spring-cloud-starter-netflix-eureka-server`)
* **API Gateway:** Spring Cloud Gateway WebFlux (`spring-cloud-starter-gateway-server-webflux`)
* **Observability / Healthchecks:** `spring-boot-starter-actuator` (required in **every** module)

> **Build Note:** Spring Boot 4.0.7 and Spring AI 2.0.0 are recent releases. Always verify Maven dependency resolution (`mvn clean test-compile`) after modifying `pom.xml`. If a Spring AI 2.0 starter or property prefix differs slightly from 1.x conventions, adapt to the actual Spring AI 2.0.0 artifact/property schema while keeping the parent versions fixed.

---

## 3. Repository & Microservice Architecture
This is a **multi-module Maven project** (`<packaging>pom</packaging>` at root) with **6 exact sub-modules** (folder names must match verbatim):

1. `eureka-server` — Service registry (`8761`, bound to `127.0.0.1` only).
2. `api-gateway` — Single public entry point (`8080`), routing via Eureka (`/ingest-static/**`, `/ingest-dynamic/**`, `/retrieval-pre/**`, `/retrieval-post/**`). Uses `spring.cloud.gateway.server.webflux.*` properties.
3. `ingest-static` — **Story 1**: Local PDF ingestion (tables & multi-column layouts), structure-aware chunking, SHA-256 checksum atomic updates, orphan-free deletion, ingestion manifest & re-indexing.
4. `ingest-dynamic` — **Story 2**: Internet ingestion (web pages, remote PDFs, third-party GitHub repo docs) with boilerplate cleanup, document versioning (`is_latest`), 30s file watcher, HMAC-verified webhooks with retry queue, and SSRF protection.
5. `retrieval-pre` — **Story 3**: Whitelist URL metadata filtering, ReDoS-safe query normalization, external acronym expansion (`classpath:acronyms.yml`), and multi-query expansion with RRF fusion.
6. `retrieval-post` — **Story 4**: Cross-encoder reranking, neighbor stitching with token budget, mandatory citations & similarity-threshold `"I don’t know."` fallback, and evaluation harness (`classpath:eval/golden-set.json`).

### Team Ownership Boundaries (Do Not Cross Without Explicit Request)
* **Laurențiu (PM & Architect):** Root POM, `docker-compose.yml`, `eureka-server`, `api-gateway`, `ingest-static` (all tasks), and `retrieval-pre` (Tasks 1 & 2: metadata filters + query normalization).
* **Alex (Security & Dynamic Ingestion):** `ingest-dynamic` (all tasks) + SSRF/security hardening.
* **Antonio (AI, Retrieval & Evaluation):** `retrieval-post` (all tasks) + `retrieval-pre` (Tasks 3 & 4: acronym expansion + multi-query expander).

---

## 4. Critical Infrastructure & Database Rules
1. **Single Schema Owner (CRITICAL):**
   * **ONLY `ingest-static`** may set `spring.ai.vectorstore.pgvector.initialize-schema=true`.
   * `ingest-dynamic`, `retrieval-pre`, and `retrieval-post` **MUST** set `initialize-schema=false` and wait for `ingest-static` in `docker-compose.yml` to prevent race conditions creating `vector_store`.
2. **Vector Dimensions (HNSW Limit):**
   * `pgvector` HNSW indexes have a hard limit of **2,000 dimensions**.
   * Set `EMBEDDING_DIMENSIONS=1536` across all services and embedding configurations.
3. **Docker Compose Startup Order:**
   * `pgvector` and `eureka-server` start first and must pass `healthcheck`.
   * `ingest-static` starts next (after `pgvector` and `eureka-server` are healthy).
   * `ingest-dynamic`, `retrieval-pre`, `retrieval-post`, and `api-gateway` start after `ingest-static`.
4. **Network & Port Exposure:**
   * Only `api-gateway` (`8080:8080`) is exposed publicly.
   * `eureka-server` (`127.0.0.1:8761:8761`) and `pgvector` (`127.0.0.1:5432:5432`) are bound strictly to localhost.
   * The 4 RAG microservices expose **no** host ports; they are reachable only via `api-gateway`.
5. **Local vs. Docker Execution:**
   * Default `application.yml` values must fall back to `localhost` for PostgreSQL and Eureka so services can also be run directly from IntelliJ outside Docker.

---

## 5. AI Models & Cost-Optimization Strategy
To satisfy the core goal of high quality with minimal operating cost:
* **Embeddings:** Google AI Studio — `gemini-embedding-2` (or `gemini-embedding-001`) configured with output dimensionality `1536`.
* **LLM (Generation & Multi-Query Expansion):** Google AI Studio — `gemini-3.5-flash-lite` (low latency, minimal cost).
  * *Integration Note:* Use Spring AI's Google GenAI starter (`spring-ai-starter-model-google-genai`) or OpenAI-compatible endpoint (`https://generativelanguage.googleapis.com/v1beta/openai/`) with `LLM_API_KEY`. **Do NOT use GCP Vertex AI starters**, as we authenticate via a standard Google AI Studio API key, not GCP IAM/project-id.
* **Reranker (`retrieval-post`):** Cohere Rerank API (`rerank-v3.5`, pinned model version) via Spring `RestClient`, with graceful fallback to original vector order on API failure/timeout.

### Agreed Numeric Targets (Default `APP_*` Values)
* `APP_CHUNK_SIZE=512` (tokens), `APP_CHUNK_OVERLAP=64` (tokens)
* `APP_WATCHER_INTERVAL_SEC=30`
* `APP_INGEST_MAX_BYTES=20971520` (20 MB), `APP_INGEST_TIMEOUT_MS=10000`
* `APP_MULTI_QUERY_VARIANTS=3`, `APP_MULTI_QUERY_TIMEOUT_MS=1500`
* `APP_RERANK_TOP_N=20` $\rightarrow$ `APP_RERANK_TOP_K=5`
* `APP_TOKEN_BUDGET=2500` (neighbor stitching max context)
* `APP_SIMILARITY_THRESHOLD=0.65` (below this, return exact string: `"I don’t know."`)
* Evaluation targets: `Recall@5 >= 0.80`, `MRR >= 0.70`, `Faithfulness >= 0.90` on a `>= 35`-question golden set (including `10` out-of-scope questions).

---

## 6. Security & Environment Variable Rules
* **Zero Secrets in Git:** Never commit API keys, passwords, or webhook secrets. `.env` must be in `.gitignore`.
* **Mandatory `.env` Variables (Compose must fail-fast if empty):**
  * `POSTGRES_PASSWORD`
  * `LLM_API_KEY`
  * `VENDOR_WEBHOOK_SECRET`
  * Optional/Fallback: `COHERE_API_KEY` (if empty, reranker logs a warning and falls back to original ranking).
* **SSRF Guardrails (`ingest-dynamic`):**
  * `APP_INGEST_BLOCK_PRIVATE_NETWORKS=true` must be enforced in Java code: resolve DNS before connecting and reject loopback (`127.0.0.0/8`, `::1`), site-local/private (`10/8`, `172.16/12`, `192.168/16`), link-local (`169.254/16` cloud metadata), and non-HTTPS URLs. Enforce domain allowlist (`kubernetes.io,github.com,raw.githubusercontent.com,cncf.io`).

---

## 7. Definition of Done (Mandatory for Every Task)
When implementing any task, you **MUST** deliver all of the following:
1. Clean, modular Java 21 code following Spring Boot best practices.
2. **Automated Tests:** At least **1 unit/integration test for the success path** and **1 test for the failure/edge path** for every task.
3. Structured logging (`SLF4J`) for key lifecycle events and errors.
4. Configuration properties externalized via `${APP_...}` placeholders in `application.yml` and documented in `.env.example`.
5. Module `README.md` or `SPEC.md` updated with usage/endpoints.
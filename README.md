# EduPulse AI — Cloud, DevOps & Kubernetes RAG Assistant

An enterprise-grade, multi-microservice Retrieval-Augmented Generation (RAG) system built with **Java 21**, **Spring Boot 4.0.7**, **Spring Cloud 2025.1.2**, **Spring AI 2.0.0**, and **PostgreSQL (`pgvector`)**.

---

## Architecture Overview

```text
                       ┌──────────────────────┐
                       │   Client / Postman   │
                       └──────────┬───────────┘
                                  │ :8080 (only public port)
                                  ▼
                       ┌──────────────────────┐
                       │     api-gateway      │◄──── Eureka Discovery (127.0.0.1:8761)
                       └──────────┬───────────┘      (eureka-server)
          ┌───────────────────────┼───────────────────────┬──────────────────────┐
          ▼                       ▼                       ▼                      ▼
┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐   ┌──────────────────┐
│  ingest-static   │    │  ingest-dynamic  │    │  retrieval-pre   │   │  retrieval-post  │
│  (Story 1: PDF)  │    │ (Story 2: Web/FS)│    │ (Story 3: Filter │   │ (Story 4: Rerank │
│  Schema Owner    │    │ SSRF & HMAC Guard│    │  Norm, RRF)      │   │  Stitch, Cite)   │
└────────┬─────────┘    └────────┬─────────┘    └────────┬─────────┘   └────────┬─────────┘
         └───────────────────────┴───────────────────────┴──────────────────────┘
                                             │
                                             ▼
                               ┌──────────────────────────┐
                               │   pgvector (PostgreSQL)  │
                               │   127.0.0.1:5432 (HNSW)  │
                               └──────────────────────────┘
```

### Key Design Rules
- **Single Schema Owner:** Only `ingest-static` sets `spring.ai.vectorstore.pgvector.initialize-schema=true`. All other RAG services set `false` and start after `ingest-static` is healthy.
- **Vector Dimensions:** Standardized to `1536` (`EMBEDDING_DIMENSIONS=1536`) to stay within `pgvector`'s 2,000-dimension HNSW index limit.
- **Cost-Optimized AI Stack:** Google AI Studio `gemini-embedding-001` (`1536` dims) + `gemini-3.5-flash-lite` + Cohere `rerank-v3.5` (with automatic fallback to vector order if `COHERE_API_KEY` is absent or unavailable).
- **Network Isolation:** Only `api-gateway` (`8080`) is publicly bound; `eureka-server` (`127.0.0.1:8761`) and `pgvector` (`127.0.0.1:5432`) bind to localhost only; the 4 RAG services expose no host ports.

---

## Quickstart

1. **Create `.env` from the template** and set mandatory secrets (`POSTGRES_PASSWORD`, `LLM_API_KEY`, `VENDOR_WEBHOOK_SECRET`):
   ```bash
   cp .env.example .env
   ```
2. **Build & start the full stack with Docker Compose:**
   ```bash
   docker compose up --build -d
   ```
3. **Verify health via API Gateway / Eureka:**
   - Gateway Health: `http://localhost:8080/actuator/health`
   - Eureka Dashboard (localhost only): `http://127.0.0.1:8761`

### Local Development & Verification (Maven)
```bash
mvn clean test-compile
mvn test
```

---

## Module Ownership & Specifications

| Module | Port (internal) | Owner | Branch | Responsibility & Spec |
|---|---|---|---|---|
| `eureka-server` | `8761` (`127.0.0.1`) | Laurențiu | `main` | Service discovery registry |
| `api-gateway` | `8080` (public) | Laurențiu | `main` | WebFlux gateway routing via Eureka |
| `ingest-static` | `8081` | Laurențiu | `feat/ingest-static-and-dynamic` | Story 1 (Task 1): Local PDF ingestion, structure-aware chunking & idempotency — [ingest-static/SPEC.md](ingest-static/SPEC.md) |
| `ingest-dynamic` | `8082` | Laurențiu | `feat/ingest-static-and-dynamic` | Story 2 (Tasks 1–3): Web/GitHub/remote PDF ingestion, SSRF protection, versioning, 30s watcher & HMAC webhooks — [ingest-dynamic/SPEC.md](ingest-dynamic/SPEC.md) |
| `retrieval-pre` | `8083` | Alex | `feat/retrieval-pre` | Story 3 (Tasks 1–4): Whitelisted metadata filters, ReDoS-safe normalization, acronyms, multi-query RRF — [retrieval-pre/SPEC.md](retrieval-pre/SPEC.md) |
| `retrieval-post` | `8084` | Antonio | `feat/retrieval-post` | Story 4 (Tasks 1–4): Cohere `rerank-v3.5`, neighbor stitching, `"I don’t know."` fallback, golden-set eval — [retrieval-post/SPEC.md](retrieval-post/SPEC.md) |

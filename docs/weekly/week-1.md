# Weekly Sync — Week 1 (2026-11-19)

**Sprint Focus:** Repository restructure (monolith -> 6 microservices), baseline build & Docker Compose infrastructure, module specifications.

---

## Laurențiu (PM & Architect)
- **Done:**
  - Defined architecture rules, tech stack, and numeric targets in `AGENTS.md` (Java 21, Spring Boot 4.0.7, Spring Cloud 2025.1.2, Spring AI 2.0.0, `pgvector` HNSW `1536` dims).
  - Executed Phase 0 & 1: converted monolith into multi-module Maven parent POM (`com.edupulse:edupulse-parent`) and scaffolded all 6 microservices (`eureka-server`, `api-gateway`, `ingest-static`, `ingest-dynamic`, `retrieval-pre`, `retrieval-post`) with passing `mvn clean test-compile` and `mvn test`.
  - Executed Phase 2 & 3: added multi-stage `Dockerfile` (with BuildKit `.m2` cache locking), `docker-compose.yml` (with configurable `POSTGRES_HOST_PORT` and strict startup/port rules), `.env.example`, module `SPEC.md` files, `docs/weekly/week-1.md`, and root `README.md`.
  - Verified full containerized stack startup: all 7 containers (`pgvector`, `eureka-server`, `ingest-static`, `ingest-dynamic`, `retrieval-pre`, `retrieval-post`, `api-gateway`) built and running `healthy`.
- **In Progress:**
  - Phase 4: implementing `ingest-static` (Story 1 — PDF parsing, SHA-256 atomic updates, orphan-free delete, and manifest/reconcile).
  - Phase 5: implementing `retrieval-pre` Tasks 1 & 2 (whitelisted metadata filters + ReDoS-safe query normalization).
- **Blockers:**
  - None.

---

## Alex (Security & Dynamic Ingestion)
- **Done:**
  - *(To be updated by Alex)*
- **In Progress / To Do:**
  - Implement `ingest-dynamic` (Story 2) per `ingest-dynamic/SPEC.md`:
    - Task 1: Web/GitHub/remote PDF ingestion (`POST /ingest/dynamic/url`) with `jsoup` boilerplate cleanup, `20 MB` max payload, and `10s` timeout.
    - Task 2: Document versioning (`is_latest` atomic updates on SHA-256 change).
    - Task 3: 30-second directory file watcher on `/app/resources`.
    - Task 4: HMAC-SHA256 verified webhooks (`POST /ingest/dynamic/webhook`) with retry queue.
    - Task 5: Mandatory SSRF guardrails (HTTPS-only, domain allowlist, DNS pre-resolution blocking loopback, private, and link-local IPs).
    - Automated unit & integration tests covering success and failure/SSRF/HMAC rejection paths.
- **Blockers:**
  - Waiting on `ingest-static` schema & manifest stabilization (Phase 4) if shared DB conventions are needed.

---

## Antonio (AI, Retrieval & Evaluation)
- **Done:**
  - *(To be updated by Antonio)*
- **In Progress / To Do:**
  - Implement `retrieval-pre` Tasks 3 & 4 per `retrieval-pre/SPEC.md`:
    - Task 3: External acronym expansion loaded from `classpath:acronyms.yml`.
    - Task 4: Multi-query expansion (`3` variants via `gemini-3.5-flash-lite`, `1500 ms` timeout) with RRF fusion and fallback.
  - Implement `retrieval-post` (Story 4) per `retrieval-post/SPEC.md`:
    - Task 1: Cohere `rerank-v3.5` cross-encoder reranking (`20 -> 5`) via `RestClient` with graceful fallback when `COHERE_API_KEY` is missing or fails.
    - Task 2: Neighbor stitching (`chunk_index ± 1`) within `APP_TOKEN_BUDGET=2500`.
    - Task 3: Mandatory inline citations and exact `"I don’t know."` fallback when similarity `< 0.65`.
    - Task 4: Evaluation harness on `>= 35`-question `classpath:eval/golden-set.json` (including `10` out-of-scope questions; targets: `Recall@5 >= 0.80`, `MRR >= 0.70`, `Faithfulness >= 0.90`).
    - Automated unit & integration tests covering success and failure/fallback paths.
- **Blockers:**
  - Waiting on `retrieval-pre` Tasks 1 & 2 (Phase 5) and `COHERE_API_KEY` / `LLM_API_KEY` for live evaluation runs.

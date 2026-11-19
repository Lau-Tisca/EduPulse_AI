# Execution Plan — Refactoring & Implementing Epic 1 (RAG)

> **Instructions for AI Agent (Agy):**
> Read `AGENTS.md` first. Execute this plan **one phase at a time**, verifying compilation/tests at the end of each phase before moving to the next. Do not skip verification steps.

---

## Phase 0: Clean Monolith & Create Multi-Module Parent POM
**Goal:** Convert the current single-module project into the required 6-module Maven parent structure on Spring Boot `4.0.7`, Spring Cloud `2025.1.2`, and Spring AI `2.0.0`.

1. Inspect the current root directory. Remove the monolithic `src/` folder (keep `.git`, `.gitignore`, `.env`, `.env.example`, `AGENTS.md`, `plan.md`, and docs).
2. Ensure `.gitignore` ignores `.env`, `target/`, `.idea/`, `*.iml`, and logs.
3. Rewrite the root `pom.xml` as a Maven Parent POM:
   * `<groupId>com.edupulse</groupId>`, `<artifactId>edupulse-parent</artifactId>`, `<version>0.1.0-SNAPSHOT</version>`, `<packaging>pom</packaging>`.
   * `<parent>`: `org.springframework.boot:spring-boot-starter-parent:4.0.7`.
   * `<properties>`:
     * `<java.version>21</java.version>`
     * `<spring-cloud.version>2025.1.2</spring-cloud.version>`
     * `<spring-ai.version>2.0.0</spring-ai.version>`
   * `<modules>`: `eureka-server`, `api-gateway`, `ingest-static`, `ingest-dynamic`, `retrieval-pre`, `retrieval-post`.
   * `<dependencyManagement>`: Import `spring-cloud-dependencies` (`2025.1.2`) and `spring-ai-bom` (`2.0.0`).
   * `<repositories>`: Include Maven Central, `spring-milestones` (`https://repo.spring.io/milestone`), and `spring-snapshots` (`https://repo.spring.io/snapshot`) in case patch/milestone transitive artifacts are needed.
4. **Verification:** Run `mvn validate` in root and confirm zero POM parsing errors.

---

## Phase 1: Scaffold All 6 Microservices & Baseline Configs
**Goal:** Create all 6 modules with minimal runnable Spring Boot applications, healthchecks, and default configurations so the entire project compiles cleanly.

1. **`eureka-server`**:
   * Dependencies: `spring-cloud-starter-netflix-eureka-server`, `spring-boot-starter-actuator`.
   * Main class annotated with `@EnableEurekaServer`.
   * `application.yml`: port `8761`, `eureka.client.register-with-eureka=false`, `eureka.client.fetch-registry=false`, actuator health endpoint exposed.
2. **`api-gateway`**:
   * Dependencies: `spring-cloud-starter-gateway-server-webflux`, `spring-cloud-starter-netflix-eureka-client`, `spring-boot-starter-actuator`.
   * `application.yml`: port `8080`, Eureka client enabled (default `http://localhost:8761/eureka/`), discovery locator enabled under `spring.cloud.gateway.server.webflux.discovery.locator.enabled=true` and `lower-case-service-id=true`.
3. **4 RAG Services (`ingest-static`, `ingest-dynamic`, `retrieval-pre`, `retrieval-post`)**:
   * Common dependencies: `spring-boot-starter-web`, `spring-boot-starter-actuator`, `spring-cloud-starter-netflix-eureka-client`, `spring-ai-starter-vector-store-pgvector`, and Google GenAI / OpenAI-compatible Spring AI model starters (see `AGENTS.md` Section 5).
   * Add PDF/Tika document reader starters (`spring-ai-pdf-document-reader`, `spring-ai-tika-document-reader`) to `ingest-static` and `ingest-dynamic`, plus `org.jsoup:jsoup` to `ingest-dynamic`.
   * Configure `application.yml` in each service with:
     * `spring.application.name` matching the exact module folder name.
     * `server.port=0` (or distinct defaults `8081`–`8084` overridable via env) so local runs don't collide.
     * PostgreSQL datasource defaulting to `jdbc:postgresql://localhost:5432/edupulse` (overridable via `SPRING_DATASOURCE_URL`).
     * `spring.ai.vectorstore.pgvector.dimensions=${EMBEDDING_DIMENSIONS:1536}`.
     * **CRITICAL:** `spring.ai.vectorstore.pgvector.initialize-schema=true` **ONLY** in `ingest-static`; set `false` in the other three.
     * Explicit `${APP_...}` property mappings in each service's `application.yml`.
   * Create initial placeholder files so startup never fails:
     * `retrieval-pre/src/main/resources/acronyms.yml` (populated with Kubernetes/Cloud acronyms: `K8s`, `CNI`, `RBAC`, `PVC`, `HPA`, `CRD`, `OCI`, `TLS`).
     * `retrieval-post/src/main/resources/eval/golden-set.json` (valid JSON array scaffold).
   * Add a lightweight `/health/info` or rely on `/actuator/health` in every module, plus at least one baseline context-load test per module.
4. **Verification:** Run `mvn clean test-compile` across the entire multi-module reactor. Fix any Spring Boot 4 / Spring AI 2.0 starter artifact naming discrepancies until `BUILD SUCCESS`.

---

## Phase 2: Docker Compose, Multi-Stage Dockerfile & `.env.example`
**Goal:** Containerize all 6 services with strict startup ordering and port security.

1. Create a reusable multi-stage `Dockerfile` (at root, accepting `--build-arg MODULE_NAME`) using `maven:3.9-eclipse-temurin-21` to build and `eclipse-temurin:21-jre-alpine` to run (with `curl` or `wget` installed for Docker healthchecks).
2. Create `.env.example` documenting all required and optional variables:
   * Required: `POSTGRES_PASSWORD=changeme`, `LLM_API_KEY=your_gemini_key`, `VENDOR_WEBHOOK_SECRET=your_hmac_secret`
   * Optional: `COHERE_API_KEY=`, `EMBEDDING_DIMENSIONS=1536`
   * All `APP_*` placeholders (`APP_CHUNK_SIZE`, `APP_CHUNK_OVERLAP`, `APP_WATCHER_INTERVAL_SEC`, `APP_INGEST_BLOCK_PRIVATE_NETWORKS`, `APP_INGEST_ALLOWED_DOMAINS`, `APP_MULTI_QUERY_VARIANTS`, `APP_RERANK_TOP_N`, `APP_RERANK_TOP_K`, `APP_TOKEN_BUDGET`, `APP_SIMILARITY_THRESHOLD`).
3. Create `./resources/.gitkeep` (directory mounted read-only into `ingest-static` and `ingest-dynamic`).
4. Create `docker-compose.yml` matching all architectural rules:
   * Fail-fast required env vars: `${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}`, `${LLM_API_KEY:?LLM_API_KEY is required}`, `${VENDOR_WEBHOOK_SECRET:?VENDOR_WEBHOOK_SECRET is required}`.
   * Services:
     * `pgvector` (`pgvector/pgvector:pg16`, bound to `127.0.0.1:5432:5432`, `pg_isready` healthcheck).
     * `eureka-server` (bound to `127.0.0.1:8761:8761`, actuator healthcheck).
     * `ingest-static` (`depends_on` `pgvector` healthy & `eureka-server` healthy; mounts `./resources:/app/resources:ro`; actuator healthcheck).
     * `ingest-dynamic` (`depends_on` `ingest-static` healthy; mounts `./resources:/app/resources:ro`).
     * `retrieval-pre` (`depends_on` `ingest-static` healthy).
     * `retrieval-post` (`depends_on` `ingest-static` healthy).
     * `api-gateway` (bound to `8080:8080`, `depends_on` `eureka-server` healthy).
5. **Verification:** Validate syntax with `docker compose config`.

---

## Phase 3: Team Specs & Collaboration Scaffolding
**Goal:** Set up isolated specifications per module and weekly sync logs for the 3-person team (Laurențiu, Alex, Antonio).

1. Generate `SPEC.md` inside each of the 4 RAG modules (`ingest-static/SPEC.md`, `ingest-dynamic/SPEC.md`, `retrieval-pre/SPEC.md`, `retrieval-post/SPEC.md`) detailing the exact Story tasks, acceptance criteria, API endpoints, and numeric targets from `AGENTS.md`.
2. Create `docs/weekly/week-1.md` with structured sections (`Done`, `In Progress`, `Blockers`) for **Laurențiu**, **Alex**, and **Antonio**.
3. Update root `README.md` with architecture diagram summary, quickstart instructions (`cp .env.example .env`, `docker compose up --build -d`), and module ownership table.

---

## Phase 4: Implement `ingest-static` (Story 1 — Tasks 1 to 4)
**Goal:** Deliver complete static PDF ingestion, atomic checksum updates, deletion with orphan checks, and manifest/re-index support.

1. **Task 1 — PDF Ingestion (`POST /ingest/static`):**
   * Parse PDFs (including multi-column layouts and tables) using Spring AI PDF/Tika readers.
   * Perform structure-aware chunking (`APP_CHUNK_SIZE=512`, `APP_CHUNK_OVERLAP=64`).
   * Attach full metadata to every `Document` chunk: `doc_id`, `version`, `source`, `page`, `chunk_index`, `checksum` (SHA-256 of file content), `is_latest=true`.
   * Ensure idempotency: re-running ingestion on an unchanged file creates zero duplicates.
2. **Task 2 — Atomic Update Process:**
   * Compare incoming file SHA-256 checksum against the manifest/store. Skip if unchanged.
   * Wrap deletion of old chunks + insertion of new chunks in a single database `@Transactional` boundary so concurrent queries never observe partial/empty state, and mid-update failures roll back cleanly leaving the previous version intact.
3. **Task 3 — Delete Process (`DELETE /ingest/static/{docId}`):**
   * Delete all chunks matching `doc_id`.
   * Run a verification query confirming zero orphaned vectors remain for `doc_id`.
   * Deleting a non-existent `docId` returns `200 OK` or `404 Not Found` with a clear structured JSON message (never `500`).
4. **Task 4 — Ingestion Manifest & Re-Index (`GET /ingest/static/manifest`, `POST /ingest/static/reindex`):**
   * Persist an `ingestion_manifest` table in PostgreSQL tracking `doc_id`, `checksum`, `version`, `ingested_at`, `embedding_model`, and `chunk_count`.
   * Implement a reconciliation endpoint (`GET /ingest/static/reconcile`) verifying zero drift between `ingestion_manifest` and `vector_store` chunk counts.
   * Implement full re-indexing across stored source files when switching embedding models.
5. **Verification:** Write unit + integration tests covering success and failure paths for all 4 tasks and run `mvn test -pl ingest-static`.

---

## Phase 5: Implement `retrieval-pre` Tasks 1 & 2 (Laurențiu's Slice)
**Goal:** Deliver safe URL metadata filtering and ReDoS-safe query normalization in `retrieval-pre`.

1. **Task 1 — Safe Metadata Filtering:**
   * Parse URL filter query parameters into a strongly-typed `ValidatedFilter` object (raw strings are never concatenated or passed directly to the vector store).
   * Enforce a strict whitelist of fields (`doc_id`, `version`, `source`, `page`, `is_latest`) and operators (`EQ`, `IN`, `AND`, `OR`).
   * Return `400 Bad Request` with a clear JSON error listing all valid fields/operators when an invalid filter is supplied.
   * Null/empty filter falls through cleanly to standard default retrieval (`is_latest=true`).
   * Commit a Postman collection (`retrieval-pre/postman/retrieval-pre-filters.postman_collection.json`) demonstrating single and multi-variable `AND`/`OR` filters.
2. **Task 2 — Query Normalization:**
   * Implement an ordered pipeline of precompiled (`static final Pattern`), idempotent, ReDoS-safe regex rules.
   * Strip polite prefixes (e.g., `"Hi, can you please tell me..."`, `"Could you explain..."`) and trailing question marks/whitespace.
   * Preserve and log the original raw query (`SLF4J`) alongside the normalized query.
   * Write a parameterized JUnit test suite with **at least 15 test cases** plus an adversarial ReDoS input test asserting execution completes in `< 50 ms`.
3. **Verification:** Run `mvn test -pl retrieval-pre` and confirm all tests pass.
# `retrieval-pre` — Technical Specification (Story 3)

- **Owners:**
  - **Laurențiu:** Tasks 1 & 2 (Metadata Filters + Query Normalization)
  - **Antonio:** Tasks 3 & 4 (Acronym Expansion + Multi-Query Expander)
- **Service Name:** `retrieval-pre` (`server.port: ${SERVER_PORT:8083}`)
- **Schema Role:** `spring.ai.vectorstore.pgvector.initialize-schema=false` (waits for `ingest-static` healthy).

---

## Numeric & Configuration Targets
| Parameter | Default | Env Variable |
|---|---|---|
| Embedding Dimensions | `1536` | `EMBEDDING_DIMENSIONS` |
| Multi-Query Variants | `3` | `APP_MULTI_QUERY_VARIANTS` |
| Multi-Query Timeout | `1500` ms | `APP_MULTI_QUERY_TIMEOUT_MS` |
| Acronyms Dictionary | `classpath:acronyms.yml` | `APP_ACRONYMS_RESOURCE` |

---

## Story 3 Tasks & Acceptance Criteria

### Task 1 — Safe Metadata Filtering *(Laurențiu)*
- Parse URL filter parameters into a strongly-typed `ValidatedFilter` object (never concatenate raw strings into vector store queries).
- Enforce a strict field whitelist (`doc_id`, `version`, `source`, `page`, `is_latest`) and operator whitelist (`EQ`, `IN`, `AND`, `OR`).
- Return `400 Bad Request` with a clear JSON error listing valid fields and operators when invalid filters are supplied.
- Null/empty filter falls through cleanly to default retrieval (`is_latest=true`).
- Include Postman collection at `retrieval-pre/postman/retrieval-pre-filters.postman_collection.json`.

### Task 2 — Query Normalization *(Laurențiu)*
- Ordered pipeline of precompiled (`static final Pattern`), idempotent, ReDoS-safe regex rules.
- Strip polite conversational prefixes (e.g., `"Hi, can you please tell me..."`, `"Could you explain..."`) and trailing question marks/whitespace.
- Preserve and log the raw original query alongside the normalized query via `SLF4J`.
- Parameterized JUnit test suite with `>= 15` test cases plus an adversarial ReDoS input test asserting `< 50 ms` completion.

### Task 3 — External Acronym Expansion *(Antonio)*
- Load Cloud/DevOps/Kubernetes acronyms from `classpath:acronyms.yml` (`K8s`, `CNI`, `RBAC`, `PVC`, `HPA`, `CRD`, `OCI`, `TLS`, etc.).
- Expand recognized acronyms cleanly without altering unknown tokens.

### Task 4 — Multi-Query Expansion with RRF Fusion *(Antonio)*
- Generate `APP_MULTI_QUERY_VARIANTS=3` query variants using `gemini-3.5-flash-lite` within `APP_MULTI_QUERY_TIMEOUT_MS=1500` ms (graceful fallback to single normalized query on timeout/error).
- Fuse candidate retrieval results using Reciprocal Rank Fusion (RRF).

---

## Definition of Done
- Success + failure/edge tests for all 4 tasks (`mvn test -pl retrieval-pre`).
- Exposed actuator health endpoint at `/actuator/health`.

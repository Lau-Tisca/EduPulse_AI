# `retrieval-post` — Technical Specification (Story 4)

- **Owner:** Antonio (AI, Retrieval & Evaluation) — **Branch:** `feat/retrieval-post`
- **Epic 1 Scope:** **Story 4 Complete (Tasks 1, 2, 3, 4)** (per `epic1_team_plan.md`)
- **Service Name:** `retrieval-post` (`server.port: ${SERVER_PORT:8084}`)
- **Schema Role:** `spring.ai.vectorstore.pgvector.initialize-schema=false` (waits for `ingest-static` healthy).

---

## Numeric & Quality Targets
| Parameter | Default | Env Variable |
|---|---|---|
| Rerank Candidates (`topN` -> `topK`) | `20` -> `5` | `APP_RERANK_TOP_N` / `APP_RERANK_TOP_K` |
| Rerank Model | `rerank-v3.5` | `APP_RERANK_MODEL` |
| Cohere API Key | *(optional)* | `COHERE_API_KEY` |
| Neighbor Stitching Token Budget | `2500` | `APP_TOKEN_BUDGET` |
| Similarity Threshold | `0.65` | `APP_SIMILARITY_THRESHOLD` |
| Golden Evaluation Set | `classpath:eval/golden-set.json` | `APP_EVAL_GOLDEN_SET_RESOURCE` |

### Evaluation Gates
- `Recall@5 >= 0.80`
- `MRR >= 0.70`
- `Faithfulness >= 0.90`
- Golden set size: `>= 35` questions (including `10` out-of-scope questions expecting `"I don’t know."`).

---

## Story 4 Tasks & Acceptance Criteria

### Task 1 — Cross-Encoder Reranking
- Call Cohere Rerank API (`rerank-v3.5`) via Spring `RestClient` to rerank top `APP_RERANK_TOP_N=20` candidates down to `APP_RERANK_TOP_K=5`.
- If `COHERE_API_KEY` is empty or the Cohere API fails/times out, log a warning and gracefully fall back to the original vector similarity ordering.

### Task 2 — Neighbor Stitching with Token Budget
- Expand top chunks with adjacent sibling chunks (`chunk_index - 1`, `chunk_index + 1`) for the same `doc_id` and `version`.
- Deduplicate overlapping chunks and respect `APP_TOKEN_BUDGET=2500` max context tokens.

### Task 3 — Mandatory Citations & `"I don’t know."` Fallback
- If the maximum similarity/relevance score is below `APP_SIMILARITY_THRESHOLD=0.65` (or no relevant context passes threshold), skip LLM generation and return the exact string `"I don’t know."`.
- Otherwise, generate a concise grounded answer via `gemini-3.5-flash-lite` with mandatory inline citations (`doc_id`, `source`, `page`/`chunk_index`).

### Task 4 — Evaluation Harness
- Load `classpath:eval/golden-set.json` and expose an evaluation runner computing `Recall@5`, `MRR`, `Faithfulness`, and out-of-scope refusal accuracy against the agreed numeric gates.

---

## Definition of Done
- Success + failure/fallback tests (including Cohere fallback and `< 0.65` threshold refusal) via `mvn test -pl retrieval-post`.
- Exposed actuator health endpoint at `/actuator/health`.

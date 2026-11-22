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
| Rerank API Timeout (connect/read, ms) | `3000` | `APP_RERANK_TIMEOUT_MS` |
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

#### Endpoint: `POST /rerank` (via gateway: `POST http://localhost:8080/retrieval-post/rerank`)
Runs a vector search for the top `APP_RERANK_TOP_N` latest chunks (`is_latest == true`), then reranks them with Cohere down to `APP_RERANK_TOP_K`. Until `retrieval-pre` hands over its fused candidates, `retrieval-post` fetches candidates itself.

```bash
curl -X POST http://localhost:8080/retrieval-post/rerank \
  -H "Content-Type: application/json" \
  -d '{"query":"How does Kubernetes RBAC work?"}'
```

```json
{
  "query": "How does Kubernetes RBAC work?",
  "reranked": true,
  "fallbackReason": null,
  "model": "rerank-v3.5",
  "candidates": 20,
  "latencyMs": 412,
  "results": [
    { "rank": 1, "id": "…", "docId": "cncf-cloud-native-security-whitepaper", "version": 1,
      "source": "cncf-cloud-native-security-whitepaper.pdf", "page": 31, "chunkIndex": 44,
      "content": "…", "vectorScore": 0.71, "rerankScore": 0.93 }
  ]
}
```

- `reranked=false` + `fallbackReason` (`MISSING_API_KEY`, `TIMEOUT`, `API_ERROR`, `INVALID_RESPONSE`): Cohere was skipped or failed; results keep vector similarity order and `rerankScore` is `null`.
- `400 Bad Request`: missing/blank `query`, query longer than 1000 characters, or malformed JSON.
- `503 Service Unavailable`: vector search (embedding API or pgvector) failed.

### Task 2 — Neighbor Stitching with Token Budget
- Expand top chunks with adjacent sibling chunks (`chunk_index - 1`, `chunk_index + 1`) for the same `doc_id` and `version`.
- Deduplicate overlapping chunks and respect `APP_TOKEN_BUDGET=2500` max context tokens.

#### Endpoint: `POST /context` (via gateway: `POST http://localhost:8080/retrieval-post/context`)
Vector search → Cohere rerank → neighbor stitching. Returns the budget-bounded context that answer generation (Task 3) will use.

```bash
curl -X POST http://localhost:8080/retrieval-post/context \
  -H "Content-Type: application/json" \
  -d '{"query":"How does Kubernetes RBAC work?"}'
```

```json
{
  "query": "How does Kubernetes RBAC work?",
  "reranked": true, "fallbackReason": null, "model": "rerank-v3.5", "candidates": 20,
  "tokenBudget": 2500, "totalTokens": 2222, "droppedHits": 0, "neighborsAvailable": true,
  "passages": [
    { "rank": 1, "docId": "cncf-cloud-native-security-whitepaper", "version": 1,
      "source": "cncf-cloud-native-security-whitepaper.pdf", "pages": [28, 29],
      "chunkIndexes": [33, 34], "anchorChunkIndexes": [34],
      "rerankScore": 0.33, "vectorScore": 0.60, "tokenCount": 576, "text": "…" }
  ]
}
```

**Stitching rules**
- Neighbors (`chunk_index ± 1`, same `doc_id` + `version`) are loaded by position from `vector_store` (no embedding call).
- Adjacent/overlapping chunks are merged into one contiguous passage; each chunk appears once and the text repeated by ingestion overlap (`APP_CHUNK_OVERLAP`) is removed when joining.
- Token budget (`APP_TOKEN_BUDGET`, counted with the same CL100K tokenizer as ingestion): reranked hits first in rank order (a hit that does not fit is dropped whole → `droppedHits`), then neighbors in the order of their hit's rank. `totalTokens <= tokenBudget` always.
- If the neighbor lookup fails, the hits are returned without neighbors and `neighborsAvailable=false`.
- Passages are ordered by `rank` (best rerank position of the hits they contain).

### Task 3 — Mandatory Citations & `"I don’t know."` Fallback
- If the maximum similarity/relevance score is below `APP_SIMILARITY_THRESHOLD=0.65` (or no relevant context passes threshold), skip LLM generation and return the exact string `"I don’t know."`.
- Otherwise, generate a concise grounded answer via `gemini-3.5-flash-lite` with mandatory inline citations (`doc_id`, `source`, `page`/`chunk_index`).

### Task 4 — Evaluation Harness
- Load `classpath:eval/golden-set.json` and expose an evaluation runner computing `Recall@5`, `MRR`, `Faithfulness`, and out-of-scope refusal accuracy against the agreed numeric gates.

---

## Definition of Done
- Success + failure/fallback tests (including Cohere fallback and `< 0.65` threshold refusal) via `mvn test -pl retrieval-post`.
- Exposed actuator health endpoint at `/actuator/health`.

# Step 4: Model Corpus Search

## Goal

Let agents search similar local `.ili` examples from configurable paths without embeddings or external services.

## Components

- `interlis.knowledge.model-paths`
- `interlis.knowledge.max-model-bytes`
- `interlis.knowledge.max-search-results`
- `ch.so.agi.mcp.knowledge.ModelCorpusService`
- `ch.so.agi.mcp.knowledge.ModelCorpusTools`

## Acceptance Criteria

- `indexConfiguredModels` scans configured files/directories recursively for `.ili`.
- Oversized files and unreadable paths are reported, not fatal.
- `findSimilarModels` returns ranked lexical matches with path, model name, score, matched terms, summary, and snippet.

## Tests

- Unit test with temporary `.ili` fixtures.
- Contract test for tool schema.

## Non-Goals

- No embeddings.
- No persistent index.
- No online INTERLIS repository crawling.


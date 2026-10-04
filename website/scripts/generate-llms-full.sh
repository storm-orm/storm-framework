#!/usr/bin/env bash
#
# Generates llms-full.txt from all documentation files in sidebar order, and a
# plain Markdown copy of every docs page beside its HTML route, so an agent can
# fetch /docs/<page>.md instead of scraping the rendered page.
# Strips Docusaurus-specific syntax (imports, JSX components, frontmatter, admonitions).
#
# Usage: bash website/scripts/generate-llms-full.sh
# Run from website/ directory or repository root.

set -euo pipefail

# Resolve paths relative to this script's location.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEBSITE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DOCS_DIR="$(cd "$WEBSITE_DIR/../docs" && pwd)"
OUTPUT="$WEBSITE_DIR/static/llms-full.txt"

# Documentation files in sidebar order.
DOCS=(
  index.md
  # Getting Started
  getting-started.md
  installation.md
  first-entity.md
  first-query.md
  glossary.md
  # Agentic Coding
  ai.md
  ai-reference.md
  database-and-mcp.md
  # Core Concepts
  entities.md
  projections.md
  relationships.md
  repositories.md
  queries.md
  pagination-and-scrolling.md
  metamodel.md
  refs.md
  entity-design.md
  transactions.md
  spring-integration.md
  ktor-integration.md
  graalvm.md
  dialects.md
  testing.md
  # Advanced Topics - Entity Modeling
  converters.md
  json.md
  polymorphism.md
  entity-lifecycle.md
  serialization.md
  validation.md
  # Advanced Topics - Operations
  batch-streaming.md
  upserts.md
  write-sets.md
  # Advanced Topics - Internals
  sql-templates.md
  string-templates.md
  hydration.md
  dirty-checking.md
  entity-cache.md
  cursors.md
  streaming-design.md
  # Advanced Topics - Operational
  configuration.md
  sql-logging.md
  metrics.md
  security.md
  error-handling.md
  performance.md
  # Resources
  common-patterns.md
  comparison.md
  faq.md
  migration-from-jpa.md
  jpa-cascades-vs-write-sets.md
  # API Reference
  api-kotlin.md
  api-java.md
)

# Drift guard: every listed file must exist and every documentation file must be listed, otherwise the build
# fails. A warn-and-continue here silently narrows the generated file's coverage in both directions.
for doc in "${DOCS[@]}"; do
  if [ ! -f "$DOCS_DIR/$doc" ]; then
    echo "Error: $doc is listed in generate-llms-full.sh but does not exist in $DOCS_DIR." >&2
    exit 1
  fi
done
drift=0
for filepath in "$DOCS_DIR"/*.md; do
  name="$(basename "$filepath")"
  listed=0
  for doc in "${DOCS[@]}"; do
    if [ "$doc" = "$name" ]; then
      listed=1
      break
    fi
  done
  if [ "$listed" -eq 0 ]; then
    echo "Error: $name exists in $DOCS_DIR but is not listed in generate-llms-full.sh; add it in reading order." >&2
    drift=1
  fi
done
if [ "$drift" -ne 0 ]; then
  exit 1
fi

strip_docusaurus() {
  # Strip YAML frontmatter only at the start of the file (line 1 must be ---).
  # Then strip Docusaurus-specific syntax.
  awk '
    BEGIN { in_frontmatter = 0; frontmatter_done = 0 }
    NR == 1 && /^---$/ { in_frontmatter = 1; next }
    in_frontmatter && /^---$/ { in_frontmatter = 0; frontmatter_done = 1; next }
    in_frontmatter { next }
    /^import .* from / { next }
    { print }
  ' | sed \
    -e '/<Tabs[^>]*>/d' \
    -e '/<\/Tabs>/d' \
    -e 's/<TabItem[^>]*label="\([^"]*\)"[^>]*>/[\1]/g' \
    -e '/<\/TabItem>/d' \
    -e 's/^:::tip.*/> **Tip:**/g' \
    -e 's/^:::warning.*/> **Warning:**/g' \
    -e 's/^:::note.*/> **Note:**/g' \
    -e 's/^:::info.*/> **Info:**/g' \
    -e 's/^:::caution.*/> **Caution:**/g' \
    -e 's/^:::danger.*/> **Danger:**/g' \
    -e '/^:::$/d' \
  | sed -e '/^$/N;/^\n$/d'
}

# Write header.
cat > "$OUTPUT" <<'HEADER'
# Storm Framework - Complete Documentation

> Storm is an ORM for Kotlin 2.0+ and Java 21+, built for agentic coding and
> performance.
>
> Every detail lives in the model. Entities are plain Kotlin data classes or Java
> records: one class is the table, its keys and its relations, and the queries
> follow from it. Underneath is a thin layer over JDBC. There is no persistence
> context, no transparent lazy loading, no proxy generation, and no entity state
> management, so the code is exactly what runs. The domain model gives one-line,
> type-safe queries across relations, returning the whole entity graph in one
> statement. The CLI installs rules and skills for coding
> agents, plus a local MCP server that exposes only schema metadata while
> keeping database credentials away from the LLM. Built-in verification
> (validateSchema(), SqlCapture) lets the agent check its own work before
> anything is committed.
>
> Get started: `npx @storm-orm/cli init` (existing project) or
> `npx @storm-orm/cli demo` (empty directory)
> Website: https://orm.st
> GitHub: https://github.com/storm-orm/storm-framework
> License: Apache 2.0

HEADER

echo "# Generated: $(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$OUTPUT"
echo "" >> "$OUTPUT"

# Process each doc file.
for doc in "${DOCS[@]}"; do
  filepath="$DOCS_DIR/$doc"
  echo "========================================" >> "$OUTPUT"
  echo "## Source: $doc" >> "$OUTPUT"
  echo "========================================" >> "$OUTPUT"
  echo "" >> "$OUTPUT"

  strip_docusaurus < "$filepath" >> "$OUTPUT"

  echo "" >> "$OUTPUT"
  echo "" >> "$OUTPUT"
done

echo "Generated $OUTPUT"

# Per-page Markdown. /docs/<page> serves the latest released snapshot and
# /docs/next/<page> the docs in this repository, so each gets its Markdown copy
# from the same source: /docs/<page>.md and /docs/next/<page>.md. The output is
# generated on every build and not committed.
write_markdown_pages() {
  local src="$1" out="$2"
  mkdir -p "$out"
  for filepath in "$src"/*.md; do
    strip_docusaurus < "$filepath" > "$out/$(basename "$filepath")"
  done
}
MARKDOWN_OUT="$WEBSITE_DIR/static/docs"
LATEST_VERSION="$(node -p "require('$WEBSITE_DIR/versions.json')[0]")"
rm -rf "$MARKDOWN_OUT"
write_markdown_pages "$WEBSITE_DIR/versioned_docs/version-$LATEST_VERSION" "$MARKDOWN_OUT"
write_markdown_pages "$DOCS_DIR" "$MARKDOWN_OUT/next"

echo "Generated Markdown pages in $MARKDOWN_OUT"

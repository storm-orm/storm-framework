#!/usr/bin/env bash
#
# Generates llms-full.txt from all documentation files in sidebar order.
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
  ai.md
  ai-reference.md
  database-and-mcp.md
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

> Storm is an AI-first ORM framework for Kotlin 2.0+ and Java 21+, the gold
> standard for AI-assisted database development.
>
> It uses immutable data classes and records instead of proxied entities,
> providing type-safe queries, predictable performance, and zero hidden magic.
> Storm works perfectly standalone, but its design and tooling make it uniquely
> suited for AI-assisted development: immutable entities produce stable code,
> the CLI installs per-tool skills, and a locally running MCP server exposes
> only schema metadata (table definitions, column types, constraints) while
> shielding your database credentials and data from the LLM. Built-in
> verification (validateSchema(), SqlCapture) lets the AI validate its own work
> before anything is committed.
>
> Get started: `npx @storm-orm/cli`
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

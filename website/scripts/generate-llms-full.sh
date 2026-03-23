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
  # Core Concepts
  getting-started.md
  installation.md
  first-entity.md
  first-query.md
  entities.md
  projections.md
  relationships.md
  repositories.md
  queries.md
  pagination-and-scrolling.md
  metamodel.md
  refs.md
  transactions.md
  spring-integration.md
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
  # Advanced Topics - Internals
  sql-templates.md
  string-templates.md
  hydration.md
  dirty-checking.md
  entity-cache.md
  cursors.md
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
  glossary.md
  ai.md
  # API Reference
  api-kotlin.md
  api-java.md
)

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

> Storm is an AI-first ORM framework for Kotlin 2.0+ and Java 21+.
> It uses immutable data classes and records instead of proxied entities,
> providing type-safe queries, predictable performance, and zero hidden magic.
> Storm works perfectly standalone, but its design and tooling make it uniquely
> suited for AI-assisted development: immutable entities produce stable code,
> the CLI installs per-tool skills, and a locally running MCP server exposes
> only schema metadata (table definitions, column types, constraints) while
> shielding your database credentials and data from the LLM.
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
  if [ ! -f "$filepath" ]; then
    echo "Warning: $doc not found, skipping." >&2
    continue
  fi

  echo "========================================" >> "$OUTPUT"
  echo "## Source: $doc" >> "$OUTPUT"
  echo "========================================" >> "$OUTPUT"
  echo "" >> "$OUTPUT"

  strip_docusaurus < "$filepath" >> "$OUTPUT"

  echo "" >> "$OUTPUT"
  echo "" >> "$OUTPUT"
done

echo "Generated $OUTPUT"

import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

// The reading order of the documentation. Keep it in sync with the DOCS list in
// website/scripts/generate-llms-full.sh, which publishes the same pages in the
// same order for AI consumers and fails the build on drift.
//
// Every page under docs/ belongs in exactly one place here. A page that is
// published but absent from the sidebar is reachable only by a lucky inbound
// link, which is how installation, first-entity, first-query, glossary,
// error-handling and string-templates went unlisted for several releases.
const sidebars: SidebarsConfig = {
  docs: [
    'index',
    {
      type: 'category',
      label: 'Getting Started',
      // Collapsed by default everywhere else; the entry path stays open so the
      // first thing a new reader sees is the route from install to first query.
      collapsed: false,
      items: [
        'getting-started',
        'installation',
        'first-entity',
        'first-query',
        'glossary',
      ],
    },
    {
      type: 'category',
      label: 'Core Concepts',
      items: [
        'entities',
        'projections',
        'relationships',
        'repositories',
        'queries',
        'pagination-and-scrolling',
        'metamodel',
        'refs',
        'entity-design',
        'transactions',
        'spring-integration',
        'ktor-integration',
        'graalvm',
        'dialects',
        'testing',
      ],
    },
    {
      type: 'category',
      label: 'Advanced Topics',
      items: [
        {
          type: 'category',
          label: 'Entity Modeling',
          items: [
            'converters',
            'json',
            'polymorphism',
            'entity-lifecycle',
            'serialization',
            'validation',
          ],
        },
        {
          type: 'category',
          label: 'Operations',
          items: [
            'batch-streaming',
            'upserts',
            'write-sets',
          ],
        },
        {
          type: 'category',
          label: 'Internals',
          items: [
            'sql-templates',
            'string-templates',
            'hydration',
            'dirty-checking',
            'entity-cache',
            'cursors',
            'streaming-design',
          ],
        },
        {
          type: 'category',
          label: 'Operational',
          items: [
            'configuration',
            'sql-logging',
            'metrics',
            'security',
            'error-handling',
            'performance',
          ],
        },
      ],
    },
    {
      type: 'category',
      label: 'Resources',
      items: [
        'common-patterns',
        'comparison',
        'faq',
        'migration-from-jpa',
        'jpa-cascades-vs-write-sets',
        'ai',
        'ai-reference',
        'database-and-mcp',
      ],
    },
    {
      type: 'category',
      label: 'API Reference',
      items: [
        'api-kotlin',
        'api-java',
      ],
    },
  ],
};

export default sidebars;

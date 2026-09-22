import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const stormVersion = require('./plugins/read-storm-version');
const remarkStormVersion = require('./plugins/remark-storm-version');
const staticVersionReplace = require('./plugins/static-version-replace');
const exampleReadmes = require('./plugins/example-readmes');
const versionedDocsCanonical = require('./plugins/versioned-docs-canonical');

// The canonical repository. Every "GitHub" affordance on the site points here
// (see also src/components/tutorial/tutorialTheme.js); the organization's
// repository list is a different destination and is only linked where that
// breadth is the point.
const GITHUB_REPO = 'https://github.com/storm-orm/storm-framework';

// The community server. A permanent invite (no expiry, no use limit): Discord's
// default invites lapse after seven days, and a dead invite in the site footer
// would go unnoticed for a long time.
const DISCORD = 'https://discord.gg/SgQpcweUJD';

// The address that reaches the engineers behind Storm directly. Security and
// conduct reports have their own address, dev@orm.st.
const CONTACT_EMAIL = 'hello@orm.st';

const config: Config = {
  title: 'Storm Framework',
  tagline: 'A modern, high-performance ORM for Kotlin 2.0+ and Java 21+',
  favicon: 'img/storm-dark.png',

  // Load the marketing type pair (Inter + JetBrains Mono) on the docs pages too,
  // so the Docusaurus-chrome theme matches the custom pages (see custom.css).
  stylesheets: [
    'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&display=swap',
  ],

  url: 'https://orm.st',
  baseUrl: '/',

  organizationName: 'storm-orm',
  projectName: 'storm-framework',

  onBrokenLinks: 'throw',

  // Exposed to client-side page components (e.g. the hand-built /quickstart
  // page) so install snippets can render the resolved release version. The
  // static-version-replace plugin only rewrites static files, not src/pages
  // output, so pages read the version from here instead of @@STORM_VERSION@@.
  customFields: {
    stormVersion,
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  plugins: [
    [staticVersionReplace, { version: stormVersion }],
    // Documentation search. The index is built from the docs at build time and
    // shipped as a static asset, so search works offline and sends nothing to a
    // third party — no hosted service, no crawler, no API key to rotate.
    // Only the current docs version is indexed: the older snapshots would
    // multiply every hit by ~14 near-identical copies.
    [
      require.resolve('@easyops-cn/docusaurus-search-local'),
      {
        indexDocs: true,
        // The blog and the marketing pages are hand-built React pages served
        // outside the Docusaurus chrome, so they carry no search bar; indexing
        // them would build an index nothing can query.
        indexBlog: false,
        indexPages: false,
        docsRouteBasePath: '/docs',
        // Content dirs, used only to compute the index's content hash so a docs
        // edit busts the cached index. Both halves of the docs tree count: the
        // `current` version (../docs) and the released snapshots.
        docsDir: ['../docs', 'versioned_docs'],
        // The index is fetched with a content hash in the query, so a stale
        // index can never be served from cache after a docs change.
        hashed: true,
        // Programming docs need their stop words: "as", "in", "not" and "for"
        // are all meaningful query terms here.
        removeDefaultStopWordFilter: true,
        searchResultLimits: 8,
        searchResultContextMaxLength: 60,
        highlightSearchTermsOnTargetPage: true,
        // One index per docs version, so a search made while reading 1.13.1
        // returns 1.13.1 pages rather than every snapshot at once. Old
        // snapshots are excluded: they are near-identical copies that would
        // multiply every hit by ~14 and bloat the build for no gain.
        ignoreFiles: [/^\/docs\/\d+\.\d+\.\d+\//],
      },
    ],
    // Point versioned and `next` docs canonicals at the current /docs/ page so
    // old snapshots do not compete with the live docs (see the plugin).
    versionedDocsCanonical,
    // Renders the example-project READMEs inline at /examples/<slug>,
    // fetched from GitHub at build time (see plugins/example-readmes.js).
    exampleReadmes,
    // Redirect the old root-level doc URLs (which used to serve at `/`) to their
    // new `/docs/...` homes, so existing links, bookmarks, and SEO keep working
    // now that the landing page owns `/`.
    [
      '@docusaurus/plugin-client-redirects',
      {
        createRedirects(existingPath: string) {
          // Every doc now lives under `/docs/`; map `/docs/x` <- `/x`.
          // The docs index (`/docs/`) is intentionally NOT redirected from `/`,
          // because `/` is the landing page.
          if (existingPath.startsWith('/docs/')) {
            const rest = existingPath.slice('/docs/'.length);
            // Do not shadow real top-level pages that live under src/pages
            // (e.g. /comparison, /quickstart) with a /docs redirect.
            const reserved = new Set(['comparison', 'quickstart']);
            if (rest && !reserved.has(rest)) return ['/' + rest];
          }
          return undefined;
        },
      },
    ],
    // Privacy-friendly analytics (Plausible). Injects the site-specific
    // loader plus the init snippet into <head> on every page.
    function plausibleAnalyticsPlugin() {
      return {
        name: 'plausible-analytics',
        injectHtmlTags() {
          return {
            headTags: [
              {
                tagName: 'script',
                attributes: {
                  async: true,
                  src: 'https://plausible.io/js/pa-tkVl2-A9dQBBULkKCv59f.js',
                },
              },
              {
                tagName: 'script',
                innerHTML:
                  "window.plausible=window.plausible||function(){(plausible.q=plausible.q||[]).push(arguments)},plausible.init=plausible.init||function(i){plausible.o=i||{}};plausible.init()",
              },
            ],
          };
        },
      };
    },
  ],

  presets: [
    [
      'classic',
      {
        docs: {
          path: '../docs',
          // Docs are namespaced under `/docs` so the landing page
          // (src/pages/index.js) owns `/`. Keep this as `/docs`: reverting to `/`
          // would let the docs reclaim the homepage and hide the landing.
          // Doc versioning (`docusaurus docs:version`) only writes to
          // versioned_docs/ — it never touches src/pages — so the front page
          // is safe across doc generation.
          routeBasePath: '/docs',
          sidebarPath: './sidebars.ts',
          versions: {
            current: {
              label: 'Next',
              path: 'next',
            },
          },
          // "Edit this page" must land on a file that exists. The string form
          // appends the doc path to the docs plugin's `path`, which produced
          // `docs/../docs/<file>` for the current version and, worse,
          // `docs/versioned_docs/version-X/<file>` for released ones — a path
          // that is not in the repository at all (the snapshots live under
          // website/versioned_docs), so every edit link on the live /docs/*
          // pages opened GitHub's "create a new file" screen.
          //
          // Every version therefore points at the living source in docs/.
          // Released snapshots are frozen by policy: a correction belongs in
          // the current docs and ships with the next release, so sending an
          // editor to the snapshot would be wrong even if the path resolved.
          editUrl: ({docPath}) =>
            `${GITHUB_REPO}/edit/main/docs/${docPath}`,
          beforeDefaultRemarkPlugins: [
            [remarkStormVersion, { version: stormVersion }],
          ],
        },
        sitemap: {
          // Only advertise the current (latest) docs version to crawlers.
          // Old numbered versions and the unreleased `/docs/next` dev docs are
          // near-duplicate content that splits ranking signal across ~10 copies
          // of each page. They stay reachable via the version dropdown; we just
          // keep them out of the sitemap so Google consolidates on the canonical
          // (unversioned) `/docs/*` pages.
          createSitemapItems: async (params) => {
            const {defaultCreateSitemapItems, ...rest} = params;
            const items = await defaultCreateSitemapItems(rest);
            const filtered = items.filter(
              (item) =>
                !item.url.includes('/docs/next') &&
                !/\/docs\/\d+\.\d+\.\d+(\/|$)/.test(item.url),
            );
            // The AI-facing docs indexes are static files, so they are not in
            // the route table the sitemap is built from; add them explicitly.
            filtered.push(
              {url: 'https://orm.st/llms.txt', changefreq: 'weekly', priority: 0.5},
              {url: 'https://orm.st/llms-full.txt', changefreq: 'weekly', priority: 0.5},
            );
            return filtered;
          },
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    // TODO(campaign): replace with a purpose-built 1200x630 social card.
    image: 'img/storm.png',
    navbar: {
      title: 'ST/ORM',
      logo: {
        alt: 'Storm Logo',
        src: 'img/storm-dark.png',
        srcDark: 'img/storm-light.png',
      },
      items: [
        {
          type: 'docsVersionDropdown',
          position: 'right',
        },
        // The canonical first-run path, listed first and labelled the same as
        // the marketing CTAs so "Quickstart" means one thing across the site.
        {
          to: '/quickstart',
          label: 'Quickstart',
          position: 'left',
        },
        {
          type: 'docSidebar',
          sidebarId: 'docs',
          position: 'left',
          label: 'Documentation',
        },
        {
          to: '/tutorials/',
          label: 'Tutorials',
          position: 'left',
        },
        {
          to: '/examples/',
          label: 'Examples',
          position: 'left',
        },
        {
          to: '/comparison',
          label: 'Comparison',
          position: 'left',
        },
        {
          to: '/benchmarks',
          label: 'Benchmarks',
          position: 'left',
        },
        {
          to: '/blog/',
          label: 'Blog',
          position: 'left',
        },
        {
          href: GITHUB_REPO,
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Learn',
          items: [
            {label: 'Quickstart', to: '/quickstart'},
            {label: 'Installation', to: '/docs/installation'},
            {label: 'Entities', to: '/docs/entities'},
            {label: 'Queries', to: '/docs/queries'},
            {label: 'Tutorials', to: '/tutorials/'},
            {label: 'Example Projects', to: '/examples/'},
          ],
        },
        {
          title: 'Evaluate',
          items: [
            {label: 'Comparison', to: '/comparison'},
            {label: 'Benchmarks', to: '/benchmarks'},
            {label: 'What Storm does not do', to: '/docs/#what-storm-does-not-do'},
            {label: 'Releases', href: `${GITHUB_REPO}/releases`},
            {label: 'Changelog', href: `${GITHUB_REPO}/blob/main/CHANGELOG.md`},
            {label: 'Maven Central', href: 'https://central.sonatype.com/namespace/st.orm'},
          ],
        },
        {
          title: 'Project',
          items: [
            {label: 'GitHub', href: GITHUB_REPO},
            {label: 'Discord', href: DISCORD},
            {label: 'Issues', href: `${GITHUB_REPO}/issues`},
            {label: 'Discussions', href: `${GITHUB_REPO}/discussions`},
            {label: 'Email the engineers', href: `mailto:${CONTACT_EMAIL}`},
            {label: 'Contributing', href: `${GITHUB_REPO}/blob/main/CONTRIBUTING.md`},
            {label: 'Security policy', href: `${GITHUB_REPO}/blob/main/SECURITY.md`},
            {label: 'Apache 2.0 license', href: `${GITHUB_REPO}/blob/main/LICENSE.txt`},
          ],
        },
        {
          title: 'More',
          items: [
            {label: 'Blog', to: '/blog/'},
            {label: 'Home', to: '/'},
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} Storm Contributors.`,
    },
    prism: {
      theme: prismThemes.github,
      // Dracula, with one correction: its comment colour measures 4.06:1 on the
      // code background, just under the 4.5:1 minimum — and comments in these
      // samples carry the explanation, not decoration. One step lighter in the
      // same hue clears it at 4.8:1. It has to be patched into the theme rather
      // than overridden in CSS, because prism-react-renderer writes token
      // colours as inline styles, which no stylesheet rule can outrank.
      darkTheme: {
        ...prismThemes.dracula,
        styles: prismThemes.dracula.styles.map((entry) =>
          entry.types.includes('comment')
            ? {...entry, style: {...entry.style, color: '#6f7cae'}}
            : entry
        ),
      },
      additionalLanguages: ['java', 'kotlin', 'groovy'],
    },
    colorMode: {
      defaultMode: 'dark',
      disableSwitch: true,
      respectPrefersColorScheme: false,
    },
  } satisfies Preset.ThemeConfig,
};

export default config;

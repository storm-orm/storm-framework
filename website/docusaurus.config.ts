import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const stormVersion = require('./plugins/read-storm-version');
const remarkStormVersion = require('./plugins/remark-storm-version');
const staticVersionReplace = require('./plugins/static-version-replace');

const config: Config = {
  title: 'Storm Framework',
  tagline: 'A modern, high-performance ORM for Kotlin 2.0+ and Java 21+',
  favicon: 'img/storm-dark.png',

  url: 'https://orm.st',
  baseUrl: '/',

  organizationName: 'storm-repo',
  projectName: 'storm-framework',

  onBrokenLinks: 'warn',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  plugins: [
    [staticVersionReplace, { version: stormVersion }],
  ],

  presets: [
    [
      'classic',
      {
        docs: {
          path: '../docs',
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
          versions: {
            current: {
              label: 'Next',
              path: 'next',
            },
          },
          editUrl: 'https://github.com/storm-repo/storm-framework/edit/main/docs/',
          beforeDefaultRemarkPlugins: [
            [remarkStormVersion, { version: stormVersion }],
          ],
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    navbar: {
      title: 'Storm',
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
        {
          type: 'docSidebar',
          sidebarId: 'docs',
          position: 'left',
          label: 'Documentation',
        },
        {
          href: 'https://github.com/storm-repo/storm-framework',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {label: 'Getting Started', to: '/getting-started'},
            {label: 'Entities', to: '/entities'},
            {label: 'Queries', to: '/queries'},
          ],
        },
        {
          title: 'More',
          items: [
            {label: 'GitHub', href: 'https://github.com/storm-repo/storm-framework'},
            {label: 'Maven Central', href: 'https://central.sonatype.com/namespace/st.orm'},
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} Storm Contributors.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['java', 'kotlin', 'groovy'],
    },
    colorMode: {
      defaultMode: 'light',
      disableSwitch: false,
      respectPrefersColorScheme: true,
    },
  } satisfies Preset.ThemeConfig,
};

export default config;

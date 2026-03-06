import { themes as prismThemes } from 'prism-react-renderer';
import type { Config } from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';
import type { PluginOptions as OpenApiPluginOptions } from 'docusaurus-plugin-openapi-docs';

// ─── Search mode ──────────────────────────────────────────────────────────────
//
// STEP 1 (active now):  Local offline search — works immediately, no account needed.
//
// STEP 2 (activate when ready):  Algolia DocSearch v4 + AskAI
//   1. Apply free at https://docsearch.algolia.com  (automated, usually same-day for open-source)
//   2. In Algolia dashboard → AI Search → Create Assistant → copy the assistantId
//   3. Fill in ALGOLIA_APP_ID, ALGOLIA_SEARCH_API_KEY, ALGOLIA_INDEX_NAME, ALGOLIA_ASSISTANT_ID below
//   4. Comment out the LOCAL_SEARCH plugin block and uncomment the ALGOLIA block
//
const SEARCH_MODE: 'local' | 'algolia' = 'local';

// ── Algolia credentials (fill these in when you get them) ─────────────────────
const ALGOLIA_APP_ID         = 'YOUR_APP_ID';          // e.g. "B1G2XXXXXXX"
const ALGOLIA_SEARCH_API_KEY = 'YOUR_SEARCH_API_KEY';  // read-only, safe to commit
const ALGOLIA_INDEX_NAME     = 'transform-platform';
const ALGOLIA_ASSISTANT_ID   = 'YOUR_ASSISTANT_ID';    // from Algolia AI Search dashboard
// ──────────────────────────────────────────────────────────────────────────────

const config: Config = {
  title: 'Transform Platform',
  tagline: 'Enterprise-grade, spec-driven file ↔ event transformation engine',
  favicon: 'img/favicon.ico',

  url: 'https://avinashreddyoceans.github.io',
  baseUrl: '/transform-platform/',

  organizationName: 'avinashreddyoceans',
  projectName: 'transform-platform',
  trailingSlash: false,

  onBrokenLinks: 'warn',
  onBrokenMarkdownLinks: 'warn',

  markdown: {
    mermaid: true,
  },

  themes: [
    '@docusaurus/theme-mermaid',
    // ── Local search theme (STEP 1) ───────────────────────────────────────────
    // Disable this block when switching to Algolia (STEP 2)
    ...(SEARCH_MODE === 'local'
      ? [
          [
            require.resolve('@easyops-cn/docusaurus-search-local'),
            {
              hashed: true,
              language: ['en'],
              highlightSearchTermsOnTargetPage: true,
              explicitSearchResultPath: true,
              searchBarShortcutHint: false,
              docsRouteBasePath: '/',
            },
          ] as any,
        ]
      : []),
    // ── OpenAPI interactive docs theme ────────────────────────────────────────
    'docusaurus-theme-openapi-docs',
  ],

  plugins: [
    // ── OpenAPI docs generator ────────────────────────────────────────────────
    [
      'docusaurus-plugin-openapi-docs',
      {
        id: 'openapi',
        docsPluginId: 'classic',
        config: {
          transformplatform: {
            specPath: 'openapi.yaml',
            outputDir: 'docs/api',
            sidebarOptions: {
              groupPathsBy: 'tag',
              categoryLinkSource: 'tag',
            },
            showSchemas: true,
          } satisfies OpenApiPluginOptions['config'][string],
        },
      } satisfies OpenApiPluginOptions,
    ],

    // ── STEP 2: Algolia DocSearch v4 adapter plugin (uncomment when ready) ───
    // [
    //   '@docsearch/docusaurus-adapter',
    //   {
    //     appId: ALGOLIA_APP_ID,
    //     apiKey: ALGOLIA_SEARCH_API_KEY,
    //     indexName: ALGOLIA_INDEX_NAME,
    //     askAi: {
    //       assistantId: ALGOLIA_ASSISTANT_ID,
    //       sidePanel: true,
    //     },
    //   },
    // ],
  ],

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/avinashreddyoceans/transform-platform/edit/main/website/',
          routeBasePath: '/',
          // Required for OpenAPI docs theme to render API pages correctly
          docItemComponent: '@theme/ApiItem',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    image: 'img/transform-platform-social-card.png',
    colorMode: {
      defaultMode: 'light',
      disableSwitch: false,
      respectPrefersColorScheme: true,
    },
    mermaid: {
      theme: { light: 'neutral', dark: 'dark' },
    },

    // ── STEP 2: Algolia DocSearch v4 + AskAI (uncomment when ready) ──────────
    // docsearch: {
    //   appId: ALGOLIA_APP_ID,
    //   apiKey: ALGOLIA_SEARCH_API_KEY,
    //   indexName: ALGOLIA_INDEX_NAME,
    //   askAi: {
    //     assistantId: ALGOLIA_ASSISTANT_ID,
    //     sidePanel: true,
    //   },
    // },

    navbar: {
      title: 'Transform Platform',
      logo: {
        alt: 'Transform Platform Logo',
        src: 'img/logo.svg',
      },
      items: [
        {
          to: '/intro',
          position: 'left',
          label: 'Docs',
          activeBaseRegex: '^/(?!$|api)',
        },
        {
          to: '/integration/overview',
          position: 'left',
          label: 'Integrations',
        },
        {
          to: '/api/transform-platform-api',
          position: 'left',
          label: 'REST API',
        },
        {
          to: '/getting-started',
          position: 'left',
          label: 'Get Started',
        },
        {
          href: 'https://github.com/avinashreddyoceans/transform-platform',
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
            { label: 'Introduction',   to: '/' },
            { label: 'Getting Started', to: '/getting-started' },
            { label: 'Architecture',   to: '/architecture' },
          ],
        },
        {
          title: 'API',
          items: [
            { label: 'REST API Reference', to: '/api/transform-platform-api' },
            { label: 'FileSpecs',     to: '/api/list-specs' },
            { label: 'Transform',     to: '/api/transform-file' },
            { label: 'Integrations',  to: '/api/list-integrations' },
          ],
        },
        {
          title: 'Extend',
          items: [
            { label: 'Adding a Parser', to: '/extending/adding-a-parser' },
            { label: 'Adding a Writer', to: '/extending/adding-a-writer' },
          ],
        },
        {
          title: 'Community',
          items: [
            { label: 'GitHub', href: 'https://github.com/avinashreddyoceans/transform-platform' },
            { label: 'Issues', href: 'https://github.com/avinashreddyoceans/transform-platform/issues' },
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} Transform Platform. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['kotlin', 'json', 'bash', 'yaml'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;

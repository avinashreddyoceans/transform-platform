import { themes as prismThemes } from 'prism-react-renderer';
import type { Config } from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

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

  // Enable Mermaid diagram rendering in markdown
  markdown: {
    mermaid: true,
  },
  themes: ['@docusaurus/theme-mermaid'],

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
    // Mermaid theme follows the site color mode automatically
    mermaid: {
      theme: { light: 'neutral', dark: 'dark' },
    },
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
          activeBaseRegex: '^/(?!$)',
        },
        {
          to: '/integration/overview',
          position: 'left',
          label: 'Integrations',
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
            { label: 'Introduction', to: '/' },
            { label: 'Getting Started', to: '/getting-started' },
            { label: 'Architecture', to: '/architecture' },
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
            {
              label: 'GitHub',
              href: 'https://github.com/avinashreddyoceans/transform-platform',
            },
            {
              label: 'Issues',
              href: 'https://github.com/avinashreddyoceans/transform-platform/issues',
            },
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

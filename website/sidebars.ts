import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docs: [
    {
      type: 'doc',
      id: 'intro',
      label: '👋 Introduction',
    },
    {
      type: 'doc',
      id: 'getting-started',
      label: '🚀 Getting Started',
    },
    {
      type: 'doc',
      id: 'developer-setup',
      label: '💻 Developer Setup',
    },
    {
      type: 'doc',
      id: 'architecture',
      label: '🏗️ Architecture',
    },
    {
      type: 'doc',
      id: 'events-to-file',
      label: '🔄 Events → File Pipeline',
    },
    {
      type: 'category',
      label: '📦 Modules',
      collapsed: false,
      items: [
        'modules/overview',
        'modules/platform-common',
        'modules/platform-core',
        'modules/platform-api',
      ],
    },
    {
      type: 'category',
      label: '🔗 Integration Domain',
      collapsed: false,
      items: [
        'integration/overview',
        'integration/domain-model',
        'integration/credential-management',
        'integration/dynamic-onboarding',
        'integration/sftp',
        'integration/pipeline-integration',
        'integration/module-structure',
      ],
    },
    {
      type: 'category',
      label: '🔌 Extending the Platform',
      collapsed: true,
      items: [
        'extending/adding-a-parser',
        'extending/adding-a-writer',
        'extending/adding-correction-rules',
        'extending/adding-validation-rules',
      ],
    },
    {
      type: 'doc',
      id: 'api-reference',
      label: '📡 API Reference',
    },
    {
      type: 'doc',
      id: 'tech-stack',
      label: '🛠️ Tech Stack',
    },
    {
      type: 'category',
      label: '🤝 Contributing',
      collapsed: true,
      items: [
        'contributing/conventions',
        'contributing/testing',
        'contributing/pr-checklist',
      ],
    },
  ],
};

export default sidebars;

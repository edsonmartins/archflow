import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docs: [
    'intro',
    {
      type: 'category',
      label: 'Conceitos',
      items: [
        'conceitos/index',
        'conceitos/arquitetura',
        'conceitos/workflows',
        'conceitos/agentes',
        'conceitos/tools',
      ],
    },
    {
      type: 'category',
      label: 'Guias',
      items: [
        'guias/quickstart-dev',
        'guias/primeiro-workflow',
        'guias/building-workflows',
        'guias/custom-tools',
        'guias/agente-ia',
        'guias/rag',
        'guias/multi-agente',
        'guias/plugin-development',
        'guias/deploy-docker',
        'guias/security-rbac',
        'guias/troubleshooting',
      ],
    },
    {
      type: 'category',
      label: 'API Reference',
      items: [
        'api/rest-endpoints',
        'api/web-component',
        'api/core',
        'api/agent',
        'api/langchain4j',
        'api/streaming',
      ],
    },
    {
      type: 'category',
      label: 'Integrações',
      items: [
        'integracoes/spring-boot',
        'integracoes/mcp',
        'integracoes/observabilidade',
      ],
    },
  ],
};

export default sidebars;

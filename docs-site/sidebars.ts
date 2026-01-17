import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docs: [
    'intro',
    {
      type: 'category',
      label: 'Conceitos',
      items: [
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
        'guias/primeiro-workflow',
        'guias/agente-ia',
        'guias/rag',
        'guias/multi-agente',
      ],
    },
    {
      type: 'category',
      label: 'API Reference',
      items: [
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

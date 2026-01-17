import React from 'react';
import ComponentCreator from '@docusaurus/ComponentCreator';

export default [
  {
    path: '/en/docs',
    component: ComponentCreator('/en/docs', 'c04'),
    routes: [
      {
        path: '/en/docs',
        component: ComponentCreator('/en/docs', '729'),
        routes: [
          {
            path: '/en/docs',
            component: ComponentCreator('/en/docs', 'e30'),
            routes: [
              {
                path: '/en/docs/api/api-agent',
                component: ComponentCreator('/en/docs/api/api-agent', '044'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/api/api-core',
                component: ComponentCreator('/en/docs/api/api-core', 'd5a'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/api/api-langchain4j',
                component: ComponentCreator('/en/docs/api/api-langchain4j', 'a2f'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/api/api-streaming',
                component: ComponentCreator('/en/docs/api/api-streaming', '880'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/conceitos/agentes',
                component: ComponentCreator('/en/docs/conceitos/agentes', '002'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/conceitos/arquitetura',
                component: ComponentCreator('/en/docs/conceitos/arquitetura', '366'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/conceitos/tools',
                component: ComponentCreator('/en/docs/conceitos/tools', '6bf'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/conceitos/workflows',
                component: ComponentCreator('/en/docs/conceitos/workflows', '87f'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/guias/agente-ia',
                component: ComponentCreator('/en/docs/guias/agente-ia', 'af4'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/guias/multi-agente',
                component: ComponentCreator('/en/docs/guias/multi-agente', '430'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/guias/primeiro-workflow',
                component: ComponentCreator('/en/docs/guias/primeiro-workflow', '6ae'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/guias/rag',
                component: ComponentCreator('/en/docs/guias/rag', '1af'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/instalacao',
                component: ComponentCreator('/en/docs/instalacao', '458'),
                exact: true
              },
              {
                path: '/en/docs/integracoes/mcp',
                component: ComponentCreator('/en/docs/integracoes/mcp', '0ef'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/integracoes/observabilidade',
                component: ComponentCreator('/en/docs/integracoes/observabilidade', '8a7'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/integracoes/spring-boot',
                component: ComponentCreator('/en/docs/integracoes/spring-boot', 'a54'),
                exact: true,
                sidebar: "docs"
              },
              {
                path: '/en/docs/intro',
                component: ComponentCreator('/en/docs/intro', '37d'),
                exact: true,
                sidebar: "docs"
              }
            ]
          }
        ]
      }
    ]
  },
  {
    path: '/en/',
    component: ComponentCreator('/en/', '6c2'),
    exact: true
  },
  {
    path: '*',
    component: ComponentCreator('*'),
  },
];

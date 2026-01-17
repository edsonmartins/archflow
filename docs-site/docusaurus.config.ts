import type {Config} from '@docusaurus/types';

const config: Config = {
  title: 'archflow',
  tagline: 'Visual AI Builder para Java',
  favicon: 'img/favicon.ico',

  url: 'https://archflow.dev',
  baseUrl: '/',

  organizationName: 'archflow',
  projectName: 'archflow',

  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',

  i18n: {
    defaultLocale: 'pt-BR',
    locales: ['pt-BR', 'en'],
  },

  presets: [
    [
      'classic',
      ({
        docs: {
          sidebarPath: 'sidebars.ts',
          editUrl: 'https://github.com/edsonmartins/archflow/tree/main/docs-site',
        },
        blog: false,
        theme: {
          customCss: ['./src/css/custom.css'],
        },
      }),
    ],
  ],

  themeConfig: {
    image: 'img/archflow-logo.png',
    navbar: {
      title: 'archflow',
      logo: {
        alt: 'archflow Logo',
        src: 'img/logo.svg',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docs',
          position: 'left',
          label: 'Documentação',
        },
        {
          href: 'https://github.com/edsonmartins/archflow',
          label: 'GitHub',
          position: 'right',
        },
        {
          type: 'localeDropdown',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Documentação',
          items: [
            {label: 'Introdução', to: '/docs/intro'},
            {label: 'Instalação', to: '/docs/instalacao'},
            {label: 'Primeiro Workflow', to: '/docs/guias/primeiro-workflow'},
          ],
        },
        {
          title: 'Comunidade',
          items: [
            {label: 'GitHub', href: 'https://github.com/edsonmartins/archflow'},
          ],
        },
        {
          title: 'Mais',
          items: [
            {label: 'GitHub', href: 'https://github.com/edsonmartins/archflow'},
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} archflow. Built with Docusaurus.`,
    },
  },
  plugins: [],
};

export default config;

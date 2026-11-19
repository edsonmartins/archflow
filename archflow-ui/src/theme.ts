import { createTheme, MantineColorsTuple } from '@mantine/core'

// Single brand blue: shade 6 (#2563EB) matches the CSS token `--blue`
// (App.css), the admin logo and the `agent` node-category color, so the
// Mantine primary and the editor palette read as one identity.
const archBlue: MantineColorsTuple = [
  '#EFF6FF', '#DBEAFE', '#BFDBFE', '#93C5FD', '#60A5FA',
  '#3B82F6', '#2563EB', '#1D4ED8', '#1E40AF', '#1E3A8A',
]

export const theme = createTheme({
  primaryColor:  'archBlue',
  primaryShade:  6,
  colors:        { archBlue },
  // Maple Mono em tudo (a mesma do Linktor): texto, títulos e código. É
  // monoespaçada — fallbacks também monoespaçados, para não mudar a métrica
  // se o arquivo demorar. Self-hosted em public/fonts (SIL OFL 1.1).
  fontFamily:          "'Maple Mono', ui-monospace, 'SFMono-Regular', Menlo, monospace",
  fontFamilyMonospace: "'Maple Mono', ui-monospace, 'SFMono-Regular', Menlo, monospace",
  headings: {
    fontFamily: "'Maple Mono', ui-monospace, 'SFMono-Regular', Menlo, monospace",
    fontWeight: '600',
  },
  defaultRadius: 'md',
  // Padrões visuais das superfícies: o "como fica" mora em App.css (classes
  // af-*), aqui só ligamos cada componente à sua classe e aos tamanhos.
  components: {
    Button: { defaultProps: { radius: 'md' } },
    Input:  { defaultProps: { radius: 'md' } },
    Paper:  { defaultProps: { radius: 'lg' }, classNames: { root: 'af-paper' } },
    Card:   { defaultProps: { radius: 'lg' }, classNames: { root: 'af-paper' } },
    Table:  {
      defaultProps: { verticalSpacing: 'sm', horizontalSpacing: 'md', highlightOnHover: true },
      classNames: { table: 'af-table' },
    },
    Badge:  { defaultProps: { radius: 'sm' } },
    Alert:  { defaultProps: { radius: 'md' } },
    Tabs:   { classNames: { list: 'af-tabs-list', tab: 'af-tab' } },
    Modal:  {
      defaultProps: { radius: 'lg', overlayProps: { backgroundOpacity: 0.45, blur: 3 } },
    },
    ThemeIcon: { defaultProps: { radius: 'md' } },
  },
})

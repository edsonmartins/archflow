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
  fontFamily:    "'DM Sans', system-ui, sans-serif",
  fontFamilyMonospace: "'DM Mono', 'Fira Code', monospace",
  // Display font própria para títulos: dá identidade tipográfica ao produto
  // (DM Sans continua no corpo; DM Mono no conteúdo técnico).
  headings: {
    fontFamily: "'Bricolage Grotesque', 'DM Sans', system-ui, sans-serif",
    fontWeight: '600',
  },
  defaultRadius: 'md',
  components: {
    Button: { defaultProps: { radius: 'md' } },
    Input:  { defaultProps: { radius: 'md' } },
    Paper:  { defaultProps: { radius: 'lg' } },
  },
})

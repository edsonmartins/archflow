import { createTheme, MantineColorsTuple } from '@mantine/core'

const archBlue: MantineColorsTuple = [
  '#E6F1FB', '#B5D4F4', '#85B7EB', '#55A0E2', '#378ADD',
  '#185FA5', '#0C447C', '#042C53', '#021D38', '#010F1D',
]

export const theme = createTheme({
  primaryColor:  'archBlue',
  primaryShade:  5,
  colors:        { archBlue },
  fontFamily:    "'DM Sans', system-ui, sans-serif",
  fontFamilyMonospace: "'DM Mono', 'Fira Code', monospace",
  defaultRadius: 'md',
  components: {
    Button: { defaultProps: { radius: 'md' } },
    Input:  { defaultProps: { radius: 'md' } },
    Paper:  { defaultProps: { radius: 'lg' } },
  },
})

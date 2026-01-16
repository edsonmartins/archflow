import './App.css'
import '@mantine/core/styles.css'
import '@mantine/dates/styles.css'
import '@mantine/notifications/styles.css'
import '@mantine/spotlight/styles.css'
import { MantineProvider } from '@mantine/core';
import FlowEditorPage from './components/FlowEditorPage';

function App() {
  return (
    <MantineProvider>
      <FlowEditorPage />
    </MantineProvider>
  );
}

export default App

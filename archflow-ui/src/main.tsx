import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

// Import and register the ArchflowDesigner Web Component
import('./web-component/index').then(({ registerArchflowDesigner }) => {
  registerArchflowDesigner();
  console.log('[ArchflowDesigner] Web Component registered');
}).catch((error) => {
  console.warn('[ArchflowDesigner] Could not register Web Component:', error);
  // Web Component might be bundled separately, continue anyway
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

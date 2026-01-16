import { createApp } from 'vue'
import './style.css'
import App from './App.vue'

// Import and register the ArchflowDesigner Web Component
import('@archflow/component').then(({ registerArchflowDesigner }) => {
  registerArchflowDesigner();
  console.log('[ArchflowDesigner] Web Component registered');
}).catch((error) => {
  console.warn('[ArchflowDesigner] Could not register Web Component:', error);
});

createApp(App).mount('#app')

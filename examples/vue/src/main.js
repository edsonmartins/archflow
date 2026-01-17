import { createApp } from 'vue';
import App from './App.vue';
import './style.css';

// Registrar Web Components globalmente
// Isso é necessário para que o Vue reconheça os custom elements
const app = createApp(App);
app.mount('#app');

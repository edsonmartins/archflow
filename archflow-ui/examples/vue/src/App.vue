<script setup lang="ts">
import ArchflowDesignerTest from './components/ArchflowDesignerTest.vue'
</script>

<template>
  <div class="app">
    <header class="app-header">
      <div class="app-header__content">
        <h1>Archflow UI</h1>
        <span class="app-header__subtitle">Vue Integration Test</span>
      </div>
    </header>

    <main class="app-main">
      <ArchflowDesignerTest />
    </main>
  </div>
</template>

<style scoped>
.app {
  display: flex;
  flex-direction: column;
  height: 100vh;
  width: 100vw;
  background: #f8fafc;
}

.app-header {
  padding: 16px 24px;
  background: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.app-header__content {
  max-width: 1400px;
  margin: 0 auto;
  display: flex;
  align-items: baseline;
  gap: 16px;
}

.app-header h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #0f172a;
}

.app-header__subtitle {
  color: #64748b;
  font-size: 14px;
}

.app-main {
  flex: 1;
  overflow: hidden;
}
</style>

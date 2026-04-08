/**
 * Test setup for Web Component tests
 */

import { expect, afterEach } from 'vitest';

// Cleanup after each test
afterEach(() => {
  document.body.innerHTML = '';
});

// Extend Vitest expect with custom matchers if needed
expect.extend({});

/**
 * ThemeManager - Manages theme switching for ArchflowDesigner
 *
 * Supports:
 * - Light and dark themes
 * - System theme detection
 * - Theme persistence (localStorage)
 * - CSS custom properties for theme values
 */

// ==========================================================================
// Type Definitions
// ==========================================================================

export type Theme = 'light' | 'dark' | 'system';

export interface ThemeColors {
  // Background colors
  bgPrimary: string;
  bgSecondary: string;
  bgTertiary: string;
  bgCanvas: string;

  // Text colors
  textPrimary: string;
  textSecondary: string;
  textTertiary: string;

  // Border colors
  border: string;
  borderLight: string;

  // Accent colors
  accent: string;
  accentHover: string;
  accentText: string;

  // Status colors
  success: string;
  successBg: string;
  error: string;
  errorBg: string;
  warning: string;
  warningBg: string;
  info: string;
  infoBg: string;
}

export interface ThemeConfig {
  theme: Theme;
  persist: boolean;
  storageKey: string;
}

// ==========================================================================
// Theme Definitions
// ==========================================================================

const LIGHT_THEME: ThemeColors = {
  bgPrimary: '#ffffff',
  bgSecondary: '#f8fafc',
  bgTertiary: '#f1f5f9',
  bgCanvas: '#fafafa',

  textPrimary: '#0f172a',
  textSecondary: '#64748b',
  textTertiary: '#94a3b8',

  border: '#e2e8f0',
  borderLight: '#f1f5f9',

  accent: '#3b82f6',
  accentHover: '#2563eb',
  accentText: '#ffffff',

  success: '#166534',
  successBg: '#dcfce7',
  error: '#991b1b',
  errorBg: '#fee2e2',
  warning: '#92400e',
  warningBg: '#fef3c7',
  info: '#1e40af',
  infoBg: '#dbeafe'
};

const DARK_THEME: ThemeColors = {
  bgPrimary: '#1e293b',
  bgSecondary: '#334155',
  bgTertiary: '#475569',
  bgCanvas: '#0f172a',

  textPrimary: '#f1f5f9',
  textSecondary: '#94a3b8',
  textTertiary: '#64748b',

  border: '#475569',
  borderLight: '#334155',

  accent: '#60a5fa',
  accentHover: '#3b82f6',
  accentText: '#ffffff',

  success: '#86efac',
  successBg: '#166534',
  error: '#fca5a5',
  errorBg: '#991b1b',
  warning: '#fcd34d',
  warningBg: '#92400e',
  info: '#93c5fd',
  infoBg: '#1e40af'
};

// ==========================================================================
// ThemeManager Class
// ==========================================================================

export class ThemeManager {
  private _theme: Theme;
  private _effectiveTheme: 'light' | 'dark';
  private _persist: boolean;
  private _storageKey: string;
  private _mediaQuery: MediaQueryList | null = null;

  constructor(theme: Theme = 'light', persist = false, storageKey = 'archflow-theme') {
    this._theme = theme;
    this._persist = persist;
    this._storageKey = storageKey;
    this._effectiveTheme = this._resolveEffectiveTheme(theme);

    // Load from storage if enabled
    if (this._persist) {
      this._loadFromStorage();
    }

    // Listen for system theme changes if using 'system' theme
    this._setupSystemThemeListener();
  }

  // ==========================================================================
  // Public Properties
  // ==========================================================================

  /**
   * Current theme setting.
   */
  get theme(): Theme {
    return this._theme;
  }

  /**
   * Effective theme (always 'light' or 'dark', never 'system').
   */
  get effectiveTheme(): 'light' | 'dark' {
    return this._effectiveTheme;
  }

  /**
   * Current theme colors.
   */
  get colors(): ThemeColors {
    return this._effectiveTheme === 'dark' ? DARK_THEME : LIGHT_THEME;
  }

  // ==========================================================================
  // Public Methods
  // ==========================================================================

  /**
   * Set the theme.
   */
  setTheme(theme: Theme): void {
    this._theme = theme;
    this._effectiveTheme = this._resolveEffectiveTheme(theme);

    if (this._persist) {
      this._saveToStorage();
    }

    // Emit theme change event
    this._emitThemeChange();
  }

  /**
   * Toggle between light and dark themes.
   */
  toggle(): void {
    const newTheme: 'light' | 'dark' = this._effectiveTheme === 'light' ? 'dark' : 'light';
    this.setTheme(newTheme);
  }

  /**
   * Get a CSS custom property value for the current theme.
   */
  getColor(key: keyof ThemeColors): string {
    return this.colors[key];
  }

  /**
   * Get all CSS custom properties for the current theme.
   */
  getCssVariables(): Record<string, string> {
    const colors = this.colors;
    return {
      '--archflow-bg-primary': colors.bgPrimary,
      '--archflow-bg-secondary': colors.bgSecondary,
      '--archflow-bg-tertiary': colors.bgTertiary,
      '--archflow-bg-canvas': colors.bgCanvas,
      '--archflow-text-primary': colors.textPrimary,
      '--archflow-text-secondary': colors.textSecondary,
      '--archflow-text-tertiary': colors.textTertiary,
      '--archflow-border': colors.border,
      '--archflow-border-light': colors.borderLight,
      '--archflow-accent': colors.accent,
      '--archflow-accent-hover': colors.accentHover,
      '--archflow-accent-text': colors.accentText,
      '--archflow-success': colors.success,
      '--archflow-success-bg': colors.successBg,
      '--archflow-error': colors.error,
      '--archflow-error-bg': colors.errorBg,
      '--archflow-warning': colors.warning,
      '--archflow-warning-bg': colors.warningBg,
      '--archflow-info': colors.info,
      '--archflow-info-bg': colors.infoBg
    };
  }

  /**
   * Apply CSS variables to an element.
   */
  applyCssVariables(element: HTMLElement): void {
    const vars = this.getCssVariables();
    for (const [key, value] of Object.entries(vars)) {
      element.style.setProperty(key, value);
    }
  }

  /**
   * Cleanup listeners.
   */
  destroy(): void {
    if (this._mediaQuery) {
      this._mediaQuery.removeEventListener('change', this._handleSystemThemeChange);
      this._mediaQuery = null;
    }
  }

  // ==========================================================================
  // Private Methods
  // ==========================================================================

  private _resolveEffectiveTheme(theme: Theme): 'light' | 'dark' {
    if (theme === 'system') {
      return this._getSystemTheme();
    }
    return theme;
  }

  private _getSystemTheme(): 'light' | 'dark' {
    if (typeof window === 'undefined' || !window.matchMedia) {
      return 'light';
    }
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  private _setupSystemThemeListener(): void {
    if (typeof window === 'undefined' || !window.matchMedia) {
      return;
    }

    this._mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    this._mediaQuery.addEventListener('change', this._handleSystemThemeChange);
  }

  private _handleSystemThemeChange = (): void => {
    if (this._theme === 'system') {
      this._effectiveTheme = this._getSystemTheme();
      this._emitThemeChange();
    }
  };

  private _loadFromStorage(): void {
    if (typeof window === 'undefined' || !window.localStorage) {
      return;
    }

    try {
      const stored = window.localStorage.getItem(this._storageKey);
      if (stored && ['light', 'dark', 'system'].includes(stored)) {
        this._theme = stored as Theme;
        this._effectiveTheme = this._resolveEffectiveTheme(this._theme);
      }
    } catch {
      // Ignore storage errors
    }
  }

  private _saveToStorage(): void {
    if (typeof window === 'undefined' || !window.localStorage) {
      return;
    }

    try {
      window.localStorage.setItem(this._storageKey, this._theme);
    } catch {
      // Ignore storage errors
    }
  }

  private _emitThemeChange(): void {
    if (typeof window === 'undefined') {
      return;
    }

    const event = new CustomEvent('archflow-theme-change', {
      detail: {
        theme: this._theme,
        effectiveTheme: this._effectiveTheme,
        colors: this.colors
      },
      bubbles: true,
      composed: true
    });

    window.dispatchEvent(event);
  }
}

// ==========================================================================
// Utility Functions
// ==========================================================================

/**
 * Create a theme manager instance.
 */
export function createThemeManager(
  theme: Theme = 'light',
  persist = false
): ThemeManager {
  return new ThemeManager(theme, persist);
}

/**
 * Get the default theme for a platform.
 */
export function getDefaultTheme(): 'light' | 'dark' {
  if (typeof window === 'undefined' || !window.matchMedia) {
    return 'light';
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/**
 * Detect if the user prefers dark mode.
 */
export function prefersDarkMode(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) {
    return false;
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

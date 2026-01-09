import type { GlobalThemeOverrides } from 'naive-ui'

// Naive UI theme overrides to match existing glassmorphism style
export const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: 'rgb(252, 121, 97)',
    primaryColorHover: 'rgb(235, 92, 80)',
    primaryColorPressed: 'rgb(214, 86, 69)',
    primaryColorSuppl: 'rgb(252, 121, 97)',

    successColor: '#2ed573',
    errorColor: '#ff4757',
    warningColor: '#ffa502',

    textColorBase: '#1a1a1a',
    textColor1: '#1a1a1a',
    textColor2: '#333333',
    textColor3: '#666666',

    borderRadius: '8px',
    borderRadiusSmall: '6px',

    fontFamily:
      "'Segoe UI', -apple-system, BlinkMacSystemFont, Roboto, Oxygen, Ubuntu, Cantarell, 'Open Sans', 'Helvetica Neue', sans-serif",
  },

  Button: {
    borderRadiusMedium: '8px',
    borderRadiusSmall: '6px',
    borderRadiusLarge: '12px',
    fontWeightStrong: '600',
  },

  Input: {
    borderRadius: '8px',
    color: 'rgba(255, 255, 255, 0.5)',
    colorFocus: 'rgba(255, 255, 255, 0.65)',
    border: '1px solid transparent',
    borderHover: '1px solid rgba(255, 255, 255, 0.3)',
    borderFocus: '1px solid rgba(255, 255, 255, 0.5)',
    boxShadowFocus: '0 8px 24px rgba(0, 0, 0, 0.08)',
  },

  Select: {
    peers: {
      InternalSelection: {
        borderRadius: '8px',
        color: 'rgba(255, 255, 255, 0.5)',
        colorActive: 'rgba(255, 255, 255, 0.65)',
        border: '1px solid rgba(0, 0, 0, 0.1)',
        borderHover: '1px solid rgba(0, 0, 0, 0.15)',
        borderFocus: '1px solid rgb(252, 121, 97)',
        boxShadowFocus: 'none',
      },
      InternalSelectMenu: {
        borderRadius: '12px',
        color: 'rgba(255, 255, 255, 0.85)',
      },
    },
  },

  Modal: {
    borderRadius: '20px',
    color: 'rgba(255, 255, 255, 0.85)',
  },

  Card: {
    borderRadius: '12px',
    color: 'rgba(255, 255, 255, 0.7)',
  },

  Dropdown: {
    borderRadius: '12px',
    color: 'rgba(255, 255, 255, 0.85)',
    optionColorHover: 'rgba(0, 0, 0, 0.06)',
    optionColorActive: 'rgba(252, 121, 97, 0.15)',
  },

  Slider: {
    fillColor: 'rgb(252, 121, 97)',
    fillColorHover: 'rgb(235, 92, 80)',
    handleColor: '#fff',
    railColor: 'rgba(0, 0, 0, 0.1)',
    railColorHover: 'rgba(0, 0, 0, 0.15)',
  },

  Progress: {
    fillColor: 'rgb(252, 121, 97)',
  },

  Message: {
    borderRadius: '12px',
  },
}

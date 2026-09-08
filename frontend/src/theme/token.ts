import type { ThemeConfig } from 'antd';

/** 全局视觉基调(Phase1 定调,后续页面沿用):靛蓝主色 + 卡片化浅色布局 */
export const BRAND_COLOR = '#6366f1';

export const themeConfig: ThemeConfig = {
  token: {
    colorPrimary: BRAND_COLOR,
    colorInfo: BRAND_COLOR,
    colorSuccess: '#22c55e',
    colorWarning: '#f59e0b',
    colorError: '#ef4444',
    borderRadius: 8,
    colorBgLayout: '#f5f6fa',
    colorText: '#1f2937',
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', sans-serif",
  },
  components: {
    Layout: {
      siderBg: '#ffffff',
      headerBg: '#ffffff',
      headerHeight: 56,
    },
    Menu: {
      itemSelectedBg: '#eef2ff',
      itemSelectedColor: BRAND_COLOR,
      itemBorderRadius: 8,
      itemMarginInline: 8,
    },
    Card: {
      borderRadiusLG: 12,
    },
  },
};

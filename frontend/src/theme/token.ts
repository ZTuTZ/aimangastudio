import type { ThemeConfig } from 'antd';

/** 柔光创作工作台:颜色语义集中在主题中,页面只负责内容层级。 */
export const BRAND_COLOR = '#6056c8';

export const themeConfig: ThemeConfig = {
  token: {
    colorPrimary: BRAND_COLOR,
    colorInfo: BRAND_COLOR,
    colorSuccess: '#2f956e',
    colorWarning: '#d48a36',
    colorError: '#d45d68',
    colorBorder: '#e6e5ef',
    colorBgLayout: '#f8f8fc',
    colorBgContainer: '#ffffff',
    colorText: '#24243b',
    colorTextSecondary: '#6e6a81',
    borderRadius: 10,
    controlHeight: 38,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', sans-serif",
  },
  components: {
    Layout: {
      siderBg: '#ffffff',
      headerBg: '#ffffff',
      headerHeight: 64,
    },
    Menu: {
      itemSelectedBg: '#f0efff',
      itemSelectedColor: BRAND_COLOR,
      itemBorderRadius: 10,
      itemMarginInline: 12,
    },
    Card: {
      borderRadiusLG: 16,
    },
    Button: {
      borderRadius: 10,
      primaryShadow: '0 8px 20px rgba(96, 86, 200, 0.18)',
    },
    Table: {
      headerBg: '#f8f8fc',
      headerColor: '#6e6a81',
      rowHoverBg: '#f8f7ff',
      borderColor: '#efedf4',
    },
    Tabs: {
      itemSelectedColor: BRAND_COLOR,
      inkBarColor: BRAND_COLOR,
    },
  },
};

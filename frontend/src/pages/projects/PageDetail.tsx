import { ComingSoon } from '@/components/ComingSoon';

export function PageDetail() {
  return (
    <ComingSoon
      title="页详情"
      items={[
        '布局图 vs 成品图对比查看',
        '脚本展示与编辑',
        '重生成布局图 / 重生成成品图',
        '上色、清晰化、局部重绘（遮罩 + 提示词）',
        '单页下载',
      ]}
    />
  );
}

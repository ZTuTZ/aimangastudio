import { ComingSoon } from '@/components/ComingSoon';

export function ProjectDetail() {
  return (
    <ComingSoon
      title="作品详情"
      items={[
        'Tab 1 剧本与资产：话列表、页脚本编辑、四类资产管理',
        'Tab 2 生成成品：范围（整部/按话）+ 色彩模式 + 一键生成',
        'Tab 3 页画廊：缩略图网格、状态角标、懒加载',
        'Tab 4 任务：该作品的生成任务进度',
      ]}
    />
  );
}

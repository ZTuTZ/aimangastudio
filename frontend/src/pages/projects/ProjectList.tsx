import { ComingSoon } from '@/components/ComingSoon';

export function ProjectList() {
  return (
    <ComingSoon
      title="作品库"
      items={[
        '作品卡片网格：封面、标题、进度状态、创建时间',
        '批量上传导入：多个 TXT/DOCX 或粘贴文本，创建即自动启动第一步流水线',
        '画幅 / 色彩模式 / 风格预设设置',
        '删除作品（二次确认）',
      ]}
    />
  );
}

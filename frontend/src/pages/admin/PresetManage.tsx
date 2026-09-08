import { ComingSoon } from '@/components/ComingSoon';

export function PresetManage() {
  return (
    <ComingSoon
      title="风格预设"
      items={['预设列表（名称 / 色彩模式 / 状态）', '新建 / 编辑：风格提示词、负面提示词、参考图', '启用 / 停用 / 排序']}
    />
  );
}

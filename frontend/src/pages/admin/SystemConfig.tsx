import { ComingSoon } from '@/components/ComingSoon';

export function SystemConfig() {
  return (
    <ComingSoon
      title="系统配置"
      items={[
        'AI 通道（文本 / 生图 / 编辑）：地址、密钥、模型、超时、并发',
        '任务并发：全局 Worker 数、每用户并行数、页级并发',
        'OSS 配置与连通性测试',
        '提示词模板在线编辑（各环节，含占位符说明与恢复默认）',
      ]}
    />
  );
}

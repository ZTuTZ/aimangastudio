import { ComingSoon } from '@/components/ComingSoon';

export function TaskMonitor() {
  return (
    <ComingSoon
      title="全局任务监控"
      items={['所有用户的任务（按用户 / 类型 / 状态筛选）', '系统概览：运行中任务数、队列长度、AI 通道占用', '停止 / 重试任意任务']}
    />
  );
}

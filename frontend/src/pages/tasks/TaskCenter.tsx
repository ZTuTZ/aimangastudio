import { ComingSoon } from '@/components/ComingSoon';

export function TaskCenter() {
  return (
    <ComingSoon
      title="任务中心"
      items={[
        '我的任务列表：类型、作品、状态、进度条、成功/失败计数',
        '实时进度（SSE 推送，断线降级轮询）',
        '操作：停止 / 重试（断点续跑）/ 删除',
      ]}
    />
  );
}

import { Tag } from 'antd';
import { TASK_STATUS, type TaskVO } from '@/api/tasks';

export function TaskStatusTag({ task }: { task: Pick<TaskVO, 'status' | 'pauseRequested'> }) {
  const meta = TASK_STATUS[task.status];
  const label = task.pauseRequested && task.status === 1 ? '暂停中' : meta?.label ?? String(task.status);
  return <Tag color={meta?.color} bordered={false}>{label}</Tag>;
}

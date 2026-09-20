import { http, unwrap } from './http';
import type { PageResult } from './projects';

export interface TaskVO {
  id: number;
  userId: number;
  projectId: number | null;
  chapterId: number | null;
  taskType: string;
  status: number; // 0排队 1进行中 2成功 3失败 4部分失败 5已停止 6停止中 7已暂停
  priority: number;
  progress: number;
  totalCount: number;
  successCount: number;
  failCount: number;
  currentNo: number;
  payload: string | null;
  error: string | null;
  result: string | null;
  createTime: string;
  startTime: string | null;
  endTime: string | null;
  retryCount: number;
  maxRetryCount: number;
  lastError: string | null;
  pauseRequested?: boolean;
  projectTitle: string | null;
}

export const TASK_TYPE_LABELS: Record<string, string> = {
  SPLIT: '拆话',
  SCRIPT: '脚本生成',
  ASSET: '资产提取',
  SHEET: '角色设定表',
  ASSET_REF: '素材参考图',
  BATCH: '整本生成',
  PAGE: '页生成',
  LAYOUT: '布局图',
  COLORIZE: '上色',
  CLEAN: '清晰化',
  REPAINT: '局部重绘',
  EXPORT: '批量导出',
  MOCK: '系统测试',
};

export const TASK_STATUS: Record<number, { label: string; color: string }> = {
  0: { label: '排队中', color: 'default' },
  1: { label: '进行中', color: 'processing' },
  2: { label: '成功', color: 'success' },
  3: { label: '失败', color: 'error' },
  4: { label: '部分失败', color: 'warning' },
  5: { label: '已停止', color: 'default' },
  6: { label: '停止中', color: 'processing' },
  7: { label: '已暂停', color: 'warning' },
};

export const tasksApi = {
  list: (params: { page: number; size: number; status?: number; type?: string; projectId?: number; keyword?: string }) =>
    unwrap<PageResult<TaskVO>>(http.get('/tasks', { params })),
  get: (id: number) => unwrap<TaskVO>(http.get(`/tasks/${id}`)),
  create: (data: { projectId: number; chapterId?: number; taskType: string; payload?: unknown }) =>
    unwrap<TaskVO>(http.post('/tasks', data)),
  stop: (id: number) => unwrap<TaskVO>(http.post(`/tasks/${id}/stop`)),
  pause: (id: number) => unwrap<TaskVO>(http.post(`/tasks/${id}/pause`)),
  retry: (id: number) => unwrap<TaskVO>(http.post(`/tasks/${id}/retry`)),
  resume: (id: number) => unwrap<TaskVO>(http.post(`/tasks/${id}/resume`)),
  remove: (id: number) => unwrap<void>(http.delete(`/tasks/${id}`)),
};

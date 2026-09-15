import { http, unwrap } from './http';

export interface UserRow {
  id: number;
  username: string;
  role: 'ADMIN' | 'USER';
  status: 0 | 1;
  createTime: string;
  projectCount: number;
}

export const adminApi = {
  listUsers: () => unwrap<UserRow[]>(http.get('/admin/users')),

  createUser: (data: { username: string; password: string; role: 'ADMIN' | 'USER' }) =>
    unwrap<UserRow>(http.post('/admin/users', data)),

  updateUser: (id: number, data: { status?: 0 | 1; newPassword?: string }) =>
    unwrap<void>(http.put(`/admin/users/${id}`, data)),

  deleteUser: (id: number, confirmUsername: string) =>
    unwrap<void>(http.delete(`/admin/users/${id}`, { data: { confirmUsername } })),

  // 全局任务监控(Phase 7.2)
  monitorOverview: () => unwrap<MonitorOverview>(http.get('/admin/monitor/overview')),
  monitorActiveProjects: () => unwrap<MonitorProject[]>(http.get('/admin/monitor/active-projects')),
  listAllProjects: () => unwrap<AdminProjectRow[]>(http.get('/admin/monitor/projects')),
  batchValidate: (projectIds: number[]) =>
    unwrap<BatchExportReport>(http.post('/admin/export/batch-validate', { projectIds })),
  batchExportUrl: '/api/admin/export/batch',
};

export interface AdminProjectRow {
  id: number;
  title: string;
  status: number;
  contentUid: string;
}

export interface BatchExportIssue {
  level: string;
  message: string;
}

export interface BatchExportItem {
  projectId: number;
  valid?: boolean;
  exported?: boolean;
  chapterCount?: number;
  pageCount?: number;
  issues?: BatchExportIssue[];
  error?: string;
}

export interface BatchExportReport {
  total: number;
  success: number;
  failed: number;
  items: BatchExportItem[];
}

export interface MonitorChannel {
  channel: string;
  used: number;
  total: number;
}

export interface MonitorOverview {
  runningTasks: number;
  pendingTasks: number;
  queueLength: number;
  maxConcurrency: number;
  imageConcurrency: number;
  aiChannels: MonitorChannel[];
}

export interface MonitorStage {
  stageType: string;
  status: number;
  total: number | null;
  success: number | null;
  failed: number | null;
  progress: number | null;
}

export interface MonitorProject {
  projectId: number;
  projectTitle: string;
  taskId: number;
  taskType: string;
  taskStatus: number;
  taskProgress: number;
  stages: MonitorStage[];
}

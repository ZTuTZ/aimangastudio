import type { ReactElement } from 'react';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { BasicLayout } from '@/layouts/BasicLayout';
import { RequireAuth, RequireAdmin, RedirectIfAuthed } from '@/components/Guards';
import { Login } from '@/pages/Login';
import { ProjectList } from '@/pages/projects/ProjectList';
import { ProjectDetail } from '@/pages/projects/ProjectDetail';
import { PageDetail } from '@/pages/projects/PageDetail';
import { TaskCenter } from '@/pages/tasks/TaskCenter';
import { ChangePassword } from '@/pages/settings/ChangePassword';
import { UserManage } from '@/pages/admin/UserManage';
import { SystemConfig } from '@/pages/admin/SystemConfig';
import { PresetManage } from '@/pages/admin/PresetManage';
import { TaskMonitor } from '@/pages/admin/TaskMonitor';
import { PublishExport } from '@/pages/admin/PublishExport';
import { NotFound } from '@/pages/NotFound';

const router = createBrowserRouter([
  { path: '/login', element: <RedirectIfAuthed><Login /></RedirectIfAuthed> },
  {
    path: '/',
    element: <RequireAuth><BasicLayout /></RequireAuth>,
    children: [
      { index: true, element: <ProjectList /> },
      { path: 'projects/:id', element: <ProjectDetail /> },
      { path: 'projects/:id/pages/:pageId', element: <PageDetail /> },
      { path: 'tasks', element: <TaskCenter /> },
      { path: 'settings', element: <ChangePassword /> },
      // 管理端:仅 ADMIN
      { path: 'admin/users', element: <RequireAdmin><UserManage /></RequireAdmin> },
      { path: 'admin/configs', element: <RequireAdmin><SystemConfig /></RequireAdmin> },
      { path: 'admin/presets', element: <RequireAdmin><PresetManage /></RequireAdmin> },
      { path: 'admin/tasks', element: <RequireAdmin><TaskMonitor /></RequireAdmin> },
      { path: 'admin/export', element: <RequireAdmin><PublishExport /></RequireAdmin> },
    ],
  },
  { path: '/403', element: <NotFound title="403" subTitle="没有权限访问该页面" /> },
  { path: '*', element: <NotFound /> },
]);

export default function App(): ReactElement {
  return <RouterProvider router={router} />;
}

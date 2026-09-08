import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { BasicLayout } from '@/layouts/BasicLayout';
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
import { NotFound } from '@/pages/NotFound';

const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  {
    path: '/',
    element: <BasicLayout />,
    children: [
      { index: true, element: <ProjectList /> },
      { path: 'projects/:id', element: <ProjectDetail /> },
      { path: 'projects/:id/pages/:pageId', element: <PageDetail /> },
      { path: 'tasks', element: <TaskCenter /> },
      { path: 'settings', element: <ChangePassword /> },
      // 管理端(T2.1 接入真实角色守卫)
      { path: 'admin/users', element: <UserManage /> },
      { path: 'admin/configs', element: <SystemConfig /> },
      { path: 'admin/presets', element: <PresetManage /> },
      { path: 'admin/tasks', element: <TaskMonitor /> },
    ],
  },
  { path: '/403', element: <NotFound title="403" subTitle="没有权限访问该页面" /> },
  { path: '*', element: <NotFound /> },
]);

export default function App() {
  return <RouterProvider router={router} />;
}

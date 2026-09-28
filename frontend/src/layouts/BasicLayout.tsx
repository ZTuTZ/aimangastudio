import { App, Avatar, Badge, Button, Layout, Menu, Space, Tag, Tooltip, Typography } from 'antd';
import {BookOutlined,
  ControlOutlined,
  MonitorOutlined,
  PictureOutlined,
  SettingOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  UserOutlined, ExportOutlined } from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useAuthStore } from '@/stores/authStore';
import { authApi } from '@/api/auth';
import { tasksApi } from '@/api/tasks';
import { useSseTasks } from '@/hooks/useSseTasks';
import { BRAND_COLOR } from '@/theme/token';

const { Sider, Header, Content } = Layout;

/** 顶栏任务活动徽标:SSE 失效 + 30s 轮询兜底 */
function TaskActivityBadge() {
  const navigate = useNavigate();
  const connected = useSseTasks(true);
  const { data } = useQuery({
    queryKey: ['task-badge'],
    queryFn: () => tasksApi.list({ page: 1, size: 1, status: 1 }),
    refetchInterval: 30000,
  });
  const running = data?.total ?? 0;
  return (
    <Tooltip title={connected ? `实时推送已连接 · ${running} 个任务进行中` : `${running} 个任务进行中(推送未连接)`}>
      <Badge count={running} size="small" offset={[-2, 2]}>
        <Button icon={<ThunderboltOutlined />} onClick={() => navigate('/tasks')} />
      </Badge>
    </Tooltip>
  );
}

function selectedKeyOf(pathname: string): string {
  if (pathname === '/') return '/';
  if (pathname.startsWith('/projects/')) return '/projects';
  return '/' + pathname.split('/')[1];
}

export function BasicLayout() {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const { message } = App.useApp();
  const user = useAuthStore((s) => s.user);
  const refreshToken = useAuthStore((s) => s.refreshToken);
  const clear = useAuthStore((s) => s.clear);
  const isAdmin = user?.role === 'ADMIN';

  const onLogout = () => {
    authApi.logout(refreshToken);
    clear();
    message.success('已退出登录');
    navigate('/login', { replace: true });
  };

  return (
    <Layout className="studio-layout studio-workspace" style={{ minHeight: '100vh' }}>
      <Sider className="studio-sider" width={224} theme="light" breakpoint="lg" collapsedWidth={72} collapsible trigger={null}>
        <div className="studio-brand flex items-center gap-2 px-5 h-16">
          <div
            className="studio-brand-mark w-8 h-8 rounded-xl flex items-center justify-center text-white text-sm font-bold"
          >
            A
          </div>
          <Typography.Text strong className="studio-brand-copy">AIMangaStudio</Typography.Text>
          <Tag color="purple" className="studio-brand-copy" style={{ marginInlineEnd: 0 }}>
            v2
          </Tag>
        </div>
        <Menu
          mode="inline"
          style={{ borderInlineEnd: 'none', paddingTop: 18 }}
          selectedKeys={[selectedKeyOf(pathname)]}
          onClick={({ key }) => navigate(key)}
          items={[
            { key: '/', icon: <BookOutlined />, label: '作品库' },
            { key: '/tasks', icon: <ThunderboltOutlined />, label: '任务中心' },
            { key: '/settings', icon: <SettingOutlined />, label: '个人设置' },
            ...(isAdmin
              ? [{
                  key: 'admin-group',
                  type: 'group' as const,
                  label: '管理后台',
                  children: [
                    { key: '/admin/users', icon: <TeamOutlined />, label: '账号管理' },
                    { key: '/admin/configs', icon: <ControlOutlined />, label: '系统配置' },
                    { key: '/admin/presets', icon: <PictureOutlined />, label: '风格预设' },
                    { key: '/admin/tasks', icon: <MonitorOutlined />, label: '任务监控' },
                    { key: '/admin/export', icon: <ExportOutlined />, label: '发布导出' },
                  ],
                }]
              : []),
          ]}
        />
        <div className="studio-sider-foot">从故事到漫画，每一页都在这里。</div>
      </Sider>
      <Layout>
        <Header className="studio-header"
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <div className="studio-header-title"><span className="studio-header-dot" />创作工作台</div>
          <Space>
            <TaskActivityBadge />
            <Avatar size={30} icon={<UserOutlined />} style={{ backgroundColor: '#ebe9ff', color: BRAND_COLOR }} />
            <Typography.Text strong className="studio-user-name">{user?.username ?? '未登录'}</Typography.Text>
            {user && (
              <Tag color={isAdmin ? 'gold' : 'blue'} style={{ marginInlineEnd: 8 }}>
                {isAdmin ? '管理员' : '用户'}
              </Tag>
            )}
            <Button size="small" onClick={onLogout}>
              退出登录
            </Button>
          </Space>
        </Header>
        <Content className="studio-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}

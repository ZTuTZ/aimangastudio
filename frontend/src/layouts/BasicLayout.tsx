import { App, Avatar, Button, Layout, Menu, Space, Tag, Typography } from 'antd';
import {
  BookOutlined,
  ControlOutlined,
  MonitorOutlined,
  PictureOutlined,
  SettingOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import { authApi } from '@/api/auth';

const { Sider, Header, Content } = Layout;

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
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={224} theme="light" style={{ borderRight: '1px solid #eef0f4' }}>
        <div className="flex items-center gap-2 px-5 h-14 border-b border-[#eef0f4]">
          <div
            className="w-7 h-7 rounded-lg flex items-center justify-center text-white text-sm font-bold"
            style={{ background: 'linear-gradient(135deg,#6366f1,#a855f7)' }}
          >
            A
          </div>
          <Typography.Text strong>AIMangaStudio</Typography.Text>
          <Tag color="purple" style={{ marginInlineEnd: 0 }}>
            v2
          </Tag>
        </div>
        <Menu
          mode="inline"
          style={{ borderInlineEnd: 'none', paddingTop: 8 }}
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
                  ],
                }]
              : []),
          ]}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            borderBottom: '1px solid #eef0f4',
            paddingInline: 24,
          }}
        >
          <Space>
            <Avatar size={28} icon={<UserOutlined />} style={{ backgroundColor: '#6366f1' }} />
            <Typography.Text strong>{user?.username ?? '未登录'}</Typography.Text>
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
        <Content style={{ padding: 24, overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}

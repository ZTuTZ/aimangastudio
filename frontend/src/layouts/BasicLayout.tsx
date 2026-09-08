import { Avatar, Layout, Menu, Space, Tag, Typography } from 'antd';
import {
  BookOutlined,
  ThunderboltOutlined,
  SettingOutlined,
  UserOutlined,
  TeamOutlined,
  ControlOutlined,
  PictureOutlined,
  MonitorOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';

const { Sider, Header, Content } = Layout;

function selectedKeyOf(pathname: string): string {
  if (pathname === '/') return '/';
  const seg = '/' + pathname.split('/')[1];
  if (pathname.startsWith('/projects/')) return '/projects';
  return seg;
}

export function BasicLayout() {
  const navigate = useNavigate();
  const { pathname } = useLocation();

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
          <Tag color="indigo" style={{ marginInlineEnd: 0 }}>
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
            { type: 'group', label: '管理后台', children: [
              { key: '/admin/users', icon: <TeamOutlined />, label: '账号管理' },
              { key: '/admin/configs', icon: <ControlOutlined />, label: '系统配置' },
              { key: '/admin/presets', icon: <PictureOutlined />, label: '风格预设' },
              { key: '/admin/tasks', icon: <MonitorOutlined />, label: '任务监控' },
            ] },
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
            <Avatar size={28} icon={<UserOutlined />} />
            <Typography.Text type="secondary">未登录（T2.1 接入）</Typography.Text>
          </Space>
        </Header>
        <Content style={{ padding: 24, overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}

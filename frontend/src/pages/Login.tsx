import { Button, Card, Form, Input, Typography } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { App } from 'antd';
import { useState } from 'react';
import { authApi } from '@/api/auth';
import { useAuthStore } from '@/stores/authStore';

export function Login() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const setAuth = useAuthStore((s) => s.setAuth);
  const [loading, setLoading] = useState(false);

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      const result = await authApi.login(values.username, values.password);
      setAuth(result.accessToken, result.refreshToken, result.user);
      message.success(`欢迎回来,${result.user.username}`);
      navigate('/', { replace: true });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="h-screen flex items-center justify-center" style={{ background: 'linear-gradient(160deg,#eef2ff 0%,#f5f6fa 45%,#fdf4ff 100%)' }}>
      <Card className="w-96 shadow-lg" styles={{ body: { padding: 36 } }}>
        <div className="text-center mb-7">
          <div
            className="w-12 h-12 rounded-xl flex items-center justify-center text-white text-xl font-bold mx-auto mb-3"
            style={{ background: 'linear-gradient(135deg,#6366f1,#a855f7)' }}
          >
            A
          </div>
          <Typography.Title level={4} style={{ marginBottom: 4 }}>
            AIMangaStudio
          </Typography.Title>
          <Typography.Text type="secondary">AI 漫画生产平台</Typography.Text>
        </div>
        <Form layout="vertical" onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            登 录
          </Button>
          <Typography.Text type="secondary" className="block text-center mt-4 text-xs">
            账号由管理员创建,如需开通请联系系统管理员
          </Typography.Text>
        </Form>
      </Card>
    </div>
  );
}

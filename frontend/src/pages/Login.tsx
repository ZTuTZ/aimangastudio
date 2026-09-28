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
    <div className="studio-login">
      <section className="studio-login-story">
        <span className="studio-page-kicker">AIMANGA STUDIO</span>
        <h1>让每一个故事，<br />逐页成形。</h1>
        <p>从长篇剧本到角色素材、分镜与成品漫画，<br />在一个工作台里持续创作。</p>
        <div className="studio-login-art" aria-hidden="true"><span>故</span><span>事</span><span>漫</span></div>
      </section>
      <div className="studio-login-form-area">
        <Card className="studio-login-card">
        <div className="text-center mb-7">
          <div
            className="w-12 h-12 rounded-xl flex items-center justify-center text-white text-xl font-bold mx-auto mb-3"
            style={{ background: 'linear-gradient(135deg,#645bd0,#b59aeb)' }}
          >
            A
          </div>
          <Typography.Title level={4} style={{ marginBottom: 4 }}>
            AIMangaStudio
          </Typography.Title>
          <Typography.Text type="secondary">AI 漫画生产平台</Typography.Text>
        </div>
        <Form layout="vertical" onFinish={onFinish} size="large">
        <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" autoComplete="username" />
          </Form.Item>
        <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
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
    </div>
  );
}

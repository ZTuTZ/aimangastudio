import { Card, Form, Input, Button, Typography } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';

/** 登录页(T2.1 接入真实认证逻辑) */
export function Login() {
  return (
    <div className="h-screen flex items-center justify-center" style={{ background: '#f5f6fa' }}>
      <Card className="w-96 shadow-md" styles={{ body: { padding: 32 } }}>
        <div className="text-center mb-6">
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
        <Form layout="vertical" disabled>
          <Form.Item label="用户名">
            <Input prefix={<UserOutlined />} placeholder="账号由管理员创建" />
          </Form.Item>
          <Form.Item label="密码">
            <Input.Password prefix={<LockOutlined />} placeholder="请输入密码" />
          </Form.Item>
          <Button type="primary" block disabled>
            登录（T2.1 实现）
          </Button>
        </Form>
      </Card>
    </div>
  );
}

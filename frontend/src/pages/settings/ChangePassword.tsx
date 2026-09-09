import { App, Button, Card, Form, Input, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { authApi } from '@/api/auth';
import { useAuthStore } from '@/stores/authStore';

interface FormValues {
  oldPassword: string;
  newPassword: string;
  confirm: string;
}

export function ChangePassword() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const clear = useAuthStore((s) => s.clear);
  const [loading, setLoading] = useState(false);

  const onFinish = async (values: FormValues) => {
    setLoading(true);
    try {
      await authApi.changePassword(values.oldPassword, values.newPassword);
      message.success('密码修改成功,请使用新密码重新登录');
      clear();
      navigate('/login', { replace: true });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '修改失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card className="max-w-lg" title="修改密码">
      <Typography.Paragraph type="secondary" className="mb-6">
        修改成功后将退出登录,需要使用新密码重新登录。
      </Typography.Paragraph>
      <Form<FormValues> layout="vertical" onFinish={onFinish}>
        <Form.Item name="oldPassword" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}>
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        <Form.Item
          name="newPassword"
          label="新密码"
          rules={[
            { required: true, message: '请输入新密码' },
            { min: 6, max: 64, message: '长度需 6-64 位' },
          ]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="confirm"
          label="确认新密码"
          dependencies={['newPassword']}
          rules={[
            { required: true, message: '请再次输入新密码' },
            ({ getFieldValue }) => ({
              validator: (_, value) =>
                !value || value === getFieldValue('newPassword')
                  ? Promise.resolve()
                  : Promise.reject(new Error('两次输入的密码不一致')),
            }),
          ]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={loading}>
          提交修改
        </Button>
      </Form>
    </Card>
  );
}

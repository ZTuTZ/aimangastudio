import { App, Button, Form, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { adminApi, type UserRow } from '@/api/admin';
import { useAuthStore } from '@/stores/authStore';
import type { ColumnsType } from 'antd/es/table';

export function UserManage() {
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const me = useAuthStore((s) => s.user);
  const { data, isLoading } = useQuery({ queryKey: ['admin', 'users'], queryFn: adminApi.listUsers });

  const [createOpen, setCreateOpen] = useState(false);
  const [resetTarget, setResetTarget] = useState<UserRow | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<UserRow | null>(null);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['admin', 'users'] });

  const toggleStatus = (row: UserRow) => {
    const next: 0 | 1 = row.status === 1 ? 0 : 1;
    modal.confirm({
      title: next === 0 ? `停用账号「${row.username}」?` : `启用账号「${row.username}」?`,
      content: next === 0 ? '停用后该用户将无法登录,已有数据保留。' : '启用后该用户可正常登录。',
      okText: '确认',
      cancelText: '取消',
      onOk: async () => {
        try {
          await adminApi.updateUser(row.id, { status: next });
          message.success(next === 0 ? '已停用' : '已启用');
          invalidate();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '操作失败');
        }
      },
    });
  };

  const columns: ColumnsType<UserRow> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    {
      title: '用户名',
      dataIndex: 'username',
      render: (v: string) => <Typography.Text strong>{v}</Typography.Text>,
    },
    {
      title: '角色',
      dataIndex: 'role',
      width: 100,
      render: (r: UserRow['role']) => (r === 'ADMIN' ? <Tag color="gold">管理员</Tag> : <Tag color="blue">用户</Tag>),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s: UserRow['status']) => (s === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">停用</Tag>),
    },
    { title: '作品数', dataIndex: 'projectCount', width: 80 },
    { title: '创建时间', dataIndex: 'createTime', width: 170 },
    {
      title: '操作',
      key: 'actions',
      width: 250,
      render: (_, row) => {
        const isSelf = me?.id === row.id;
        return (
          <Space>
            <Button size="small" onClick={() => setResetTarget(row)}>
              重置密码
            </Button>
            <Button size="small" disabled={isSelf} onClick={() => toggleStatus(row)}>
              {row.status === 1 ? '停用' : '启用'}
            </Button>
            <Button size="small" danger disabled={isSelf} onClick={() => setDeleteTarget(row)}>
              删除
            </Button>
          </Space>
        );
      },
    },
  ];

  return (
    <div className="bg-white rounded-xl border border-[#eef0f4] p-6">
      <div className="flex items-center justify-between mb-4">
        <Typography.Title level={4} style={{ margin: 0 }}>
          账号管理
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          创建账号
        </Button>
      </div>
      <Table<UserRow> rowKey="id" columns={columns} dataSource={data ?? []} loading={isLoading} pagination={false} />

      <CreateUserModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          setCreateOpen(false);
          invalidate();
        }}
      />

      <ResetPasswordModal
        target={resetTarget}
        onClose={() => setResetTarget(null)}
        onDone={() => {
          setResetTarget(null);
          invalidate();
        }}
      />

      <DeleteUserModal
        target={deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onDeleted={() => {
          setDeleteTarget(null);
          invalidate();
        }}
      />
    </div>
  );
}

function CreateUserModal({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: () => void }) {
  const { message } = App.useApp();
  const [form] = Form.useForm<{ username: string; password: string; role: 'ADMIN' | 'USER' }>();
  const [loading, setLoading] = useState(false);

  const onOk = async () => {
    const values = await form.validateFields();
    setLoading(true);
    try {
      await adminApi.createUser(values);
      message.success(`账号「${values.username}」已创建`);
      form.resetFields();
      onCreated();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      open={open}
      title="创建账号"
      okText="创建"
      cancelText="取消"
      confirmLoading={loading}
      onCancel={onClose}
      onOk={onOk}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" initialValues={{ role: 'USER' }}>
        <Form.Item
          name="username"
          label="用户名"
          rules={[
            { required: true, message: '请输入用户名' },
            { min: 2, max: 64, message: '长度需 2-64 位' },
            { pattern: /^[a-zA-Z0-9_\-\u4e00-\u9fa5]+$/, message: '仅支持中英文、数字、下划线和连字符' },
          ]}
        >
          <Input placeholder="用户名" autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="password"
          label="初始密码"
          rules={[
            { required: true, message: '请输入初始密码' },
            { min: 6, max: 64, message: '长度需 6-64 位' },
          ]}
        >
          <Input.Password placeholder="初始密码" autoComplete="new-password" />
        </Form.Item>
        <Form.Item name="role" label="角色" rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'USER', label: '普通用户(仅漫画生产)' },
              { value: 'ADMIN', label: '系统管理员(全部权限)' },
            ]}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function ResetPasswordModal({
  target,
  onClose,
  onDone,
}: {
  target: UserRow | null;
  onClose: () => void;
  onDone: () => void;
}) {
  const { message } = App.useApp();
  const [form] = Form.useForm<{ newPassword: string }>();
  const [loading, setLoading] = useState(false);

  const onOk = async () => {
    if (!target) return;
    const values = await form.validateFields();
    setLoading(true);
    try {
      await adminApi.updateUser(target.id, { newPassword: values.newPassword });
      message.success(`已重置「${target.username}」的密码`);
      form.resetFields();
      onDone();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重置失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      open={!!target}
      title={`重置密码「${target?.username ?? ''}」`}
      okText="确认重置"
      cancelText="取消"
      confirmLoading={loading}
      onCancel={onClose}
      onOk={onOk}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="newPassword"
          label="新密码"
          rules={[
            { required: true, message: '请输入新密码' },
            { min: 6, max: 64, message: '长度需 6-64 位' },
          ]}
        >
          <Input.Password placeholder="新密码" autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function DeleteUserModal({
  target,
  onClose,
  onDeleted,
}: {
  target: UserRow | null;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const { message } = App.useApp();
  const [confirmText, setConfirmText] = useState('');
  const [loading, setLoading] = useState(false);
  const matched = !!target && confirmText === target.username;

  const onOk = async () => {
    if (!target) return;
    setLoading(true);
    try {
      await adminApi.deleteUser(target.id, target.username);
      message.success(`账号「${target.username}」已删除`);
      setConfirmText('');
      onDeleted();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      open={!!target}
      title="删除账号(高危操作)"
      okText="确认删除"
      okButtonProps={{ danger: true, disabled: !matched, loading }}
      cancelText="取消"
      onCancel={() => {
        setConfirmText('');
        onClose();
      }}
      onOk={onOk}
    >
      <Typography.Paragraph type="danger">
        将永久删除账号「{target?.username ?? ''}」。名下有作品时无法删除(请改用停用);删除后不可恢复。
      </Typography.Paragraph>
      <Typography.Paragraph>请输入该用户的用户名以确认:</Typography.Paragraph>
      <Input
        placeholder={target?.username}
        value={confirmText}
        onChange={(e) => setConfirmText(e.target.value)}
        autoComplete="off"
      />
    </Modal>
  );
}

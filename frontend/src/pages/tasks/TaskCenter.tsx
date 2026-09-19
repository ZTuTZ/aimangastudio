import { App, Button, Empty, Form, Input, InputNumber, Modal, Popconfirm, Progress, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { CaretRightOutlined, ReloadOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { projectsApi } from '@/api/projects';
import { tasksApi, TASK_STATUS, TASK_TYPE_LABELS, type TaskVO } from '@/api/tasks';
import { useAuthStore } from '@/stores/authStore';
import { useSseTasks } from '@/hooks/useSseTasks';
import type { ColumnsType } from 'antd/es/table';

interface AppliedFilter {
  status?: number;
  type?: string;
  keyword?: string;
}

function durationOf(row: TaskVO): string {
  if (!row.startTime) return '—';
  const end = row.endTime ? Date.parse(row.endTime.replace(' ', 'T')) : Date.now();
  const start = Date.parse(row.startTime.replace(' ', 'T'));
  const seconds = Math.max(0, Math.round((end - start) / 1000));
  return seconds >= 60 ? `${Math.floor(seconds / 60)}分${seconds % 60}秒` : `${seconds}秒`;
}

export function TaskCenter() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const connected = useSseTasks(true);

  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(12);
  const [statusInput, setStatusInput] = useState<number | undefined>();
  const [typeInput, setTypeInput] = useState<string | undefined>();
  const [keywordInput, setKeywordInput] = useState('');
  const [applied, setApplied] = useState<AppliedFilter>({});
  const [testModalOpen, setTestModalOpen] = useState(false);
  const isAdmin = useAuthStore((s) => s.user?.role === 'ADMIN');

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['tasks', page, pageSize, applied],
    queryFn: () => tasksApi.list({ page, size: pageSize, ...applied }),
    // SSE 断线时降级为 5s 轮询
    refetchInterval: connected ? false : 5000,
    placeholderData: (prev) => prev,
  });

  const applySearch = () => {
    setPage(1);
    setApplied({
      status: statusInput,
      type: typeInput,
      keyword: keywordInput.trim() || undefined,
    });
  };

  const resetSearch = () => {
    setStatusInput(undefined);
    setTypeInput(undefined);
    setKeywordInput('');
    setPage(1);
    setApplied({});
  };

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['tasks'] });

  const withTaskAction = async (task: TaskVO, action: (id: number) => Promise<unknown>, okText: string) => {
    try {
      await action(task.id);
      message.success(okText);
      refresh();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
    }
  };

  const onBatchDeleteStopped = async () => {
    const removable = (data?.records ?? []).filter((t) => [2, 3, 4, 5].includes(t.status));
    let ok = 0;
    let fail = 0;
    await Promise.all(
      removable.map(async (t) => {
        try {
          await tasksApi.remove(t.id);
          ok += 1;
        } catch {
          fail += 1;
        }
      }),
    );
    message.success(`已清理本页结束任务 ${ok} 个${fail > 0 ? `,失败 ${fail} 个` : ''}`);
    refresh();
  };

  const columns: ColumnsType<TaskVO> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    {
      title: '类型',
      dataIndex: 'taskType',
      width: 90,
      render: (t: string) => <Tag bordered={false}>{TASK_TYPE_LABELS[t] ?? t}</Tag>,
    },
    {
      title: '作品',
      dataIndex: 'projectTitle',
      width: 150,
      render: (v: string | null, row) =>
        row.projectId ? (
          <a onClick={() => navigate(`/projects/${row.projectId}`)}>{v || `#${row.projectId}`}</a>
        ) : (
          '—'
        ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s: number, row) => {
        const meta = TASK_STATUS[s] ?? { label: '未知', color: 'default' };
        return <Tag color={meta.color} bordered={false}>
          {row.pauseRequested && s === 1 ? '暂停中' : meta.label}
        </Tag>;
      },
    },
    {
      title: '进度',
      key: 'progress',
      width: 180,
      render: (_, row) => {
        const active = row.status === 1 || row.status === 6;
        return (
          <div className="flex items-center gap-2 min-w-[140px]">
            <Progress
              percent={row.status === 2 ? 100 : row.progress}
              size="small"
              status={row.status === 3 || row.status === 4 ? 'exception' : active ? 'active' : 'normal'}
              style={{ marginBottom: 0, flex: 1 }}
            />
            <span className="text-xs text-gray-400 whitespace-nowrap">
              {row.successCount}/{row.totalCount || '?'}
              {row.failCount > 0 && <span className="text-red-400"> 失{row.failCount}</span>}
            </span>
          </div>
        );
      },
    },
    { title: '耗时', key: 'duration', width: 90, render: (_, row) => durationOf(row) },
    {
      title: '错误',
      dataIndex: 'error',
      ellipsis: true,
      render: (v: string | null) =>
        v ? (
          <Tooltip title={v}>
            <Typography.Text type="danger" ellipsis className="max-w-[160px]">{v}</Typography.Text>
          </Tooltip>
        ) : (
          '—'
        ),
    },
    { title: '创建时间', dataIndex: 'createTime', width: 165 },
    {
      title: '操作',
      key: 'actions',
      width: 130,
      render: (_, row) => {
        const active = row.status === 0 || row.status === 1 || row.status === 6;
        const paused = row.status === 7;
        const finished = [2, 3, 4, 5].includes(row.status);
        return (
          <Space size={0}>
            {active && (
              <>
                {row.status === 1 && !row.pauseRequested && (
                  <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={() => withTaskAction(row, tasksApi.pause, '已请求暂停')}>
                    暂停
                  </Button>
                )}
                {row.status === 1 && row.pauseRequested && (
                  <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={() => withTaskAction(row, tasksApi.resume, '已取消暂停')}>
                    取消暂停
                  </Button>
                )}
                <Button type="link" size="small" danger style={{ paddingInline: 4 }} onClick={() => withTaskAction(row, tasksApi.stop, '已请求停止')}>
                  停止
                </Button>
              </>
            )}
            {paused && (
              <>
                <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={() => withTaskAction(row, tasksApi.resume, '任务已恢复')}>
                  继续
                </Button>
                <Button type="link" size="small" danger style={{ paddingInline: 4 }} onClick={() => withTaskAction(row, tasksApi.stop, '已停止')}>
                  停止
                </Button>
              </>
            )}
            {finished && (
              <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={() => withTaskAction(row, tasksApi.retry, '已重新入队')}>
                重试
              </Button>
            )}
            {finished && (
              <Popconfirm title={`删除任务 #${row.id}?`} okText="删除" okButtonProps={{ danger: true }} cancelText="取消"
                onConfirm={() => withTaskAction(row, tasksApi.remove, '已删除')}>
                <Button type="link" size="small" danger style={{ paddingInline: 4 }}>
                  删除
                </Button>
              </Popconfirm>
            )}
            {!active && !finished && <span className="text-xs text-gray-400">—</span>}
          </Space>
        );
      },
    },
  ];

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <div>
          <Typography.Title level={4} style={{ margin: 0 }}>
            任务中心
          </Typography.Title>
          <Typography.Text type="secondary">
            {connected ? (
              <span className="text-green-600">● 实时推送已连接</span>
            ) : (
              <span className="text-gray-400">● 实时推送未连接(5 秒轮询中)</span>
            )}
          </Typography.Text>
        </div>
        <Space>
          {isAdmin && (
            <Button icon={<CaretRightOutlined />} onClick={() => setTestModalOpen(true)}>
              创建测试任务
            </Button>
          )}
          {data && data.records.some((t) => [2, 3, 4, 5].includes(t.status)) && (
            <Button onClick={onBatchDeleteStopped}>清理本页结束任务</Button>
          )}
          <Button icon={<ReloadOutlined />} onClick={refresh}>
            刷新
          </Button>
        </Space>
      </div>

      <div className="bg-white rounded-xl border border-[#eef0f4] p-4">
        <div className="flex flex-wrap items-center gap-2 mb-4">
          <Input
            allowClear
            placeholder="按作品名过滤"
            style={{ width: 200 }}
            value={keywordInput}
            onChange={(e) => setKeywordInput(e.target.value)}
            onPressEnter={applySearch}
          />
          <Select
            allowClear
            placeholder="类型"
            style={{ width: 140 }}
            value={typeInput}
            onChange={(v) => setTypeInput(v)}
            options={Object.entries(TASK_TYPE_LABELS).map(([v, label]) => ({ value: v, label }))}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 140 }}
            value={statusInput}
            onChange={(v) => setStatusInput(v)}
            options={Object.entries(TASK_STATUS).map(([v, m]) => ({ value: Number(v), label: m.label }))}
          />
          <Button type="primary" onClick={applySearch}>查询</Button>
          <Button onClick={resetSearch}>重置</Button>
        </div>

        <Table<TaskVO>
          rowKey="id"
          columns={columns}
          dataSource={data?.records ?? []}
          loading={isLoading || isFetching}
          locale={{ emptyText: <Empty description="暂无任务 —— 上传作品后会自动创建准备任务" /> }}
          pagination={{
            current: page,
            pageSize,
            total: data?.total ?? 0,
            showSizeChanger: true,
            pageSizeOptions: [12, 24, 48],
            showTotal: (total) => `共 ${total} 个任务`,
            onChange: (p, s) => {
              setPage(p);
              setPageSize(s);
            },
          }}
        />
      </div>
      <UploadStoriesTestModal
        open={testModalOpen}
        onClose={() => setTestModalOpen(false)}
      />
    </div>
  );
}

/** 创建 MOCK 测试任务(仅管理员):验证状态机/并发/停止/重试 */
function UploadStoriesTestModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<{ projectId: number; steps: number; sleepMs: number; failAt?: number[] }>();
  const [submitting, setSubmitting] = useState(false);

  const { data: projects } = useQuery({
    queryKey: ['projects', 'for-test'],
    queryFn: () => projectsApi.list({ page: 1, size: 50 }),
    enabled: open,
  });

  const onOk = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const payload: Record<string, unknown> = { steps: values.steps, sleepMs: values.sleepMs };
      if (values.failAt && values.failAt.length > 0) {
        payload.failAt = values.failAt;
      }
      const created = await tasksApi.create({
        projectId: values.projectId,
        taskType: 'MOCK',
        payload,
      });
      message.success(`测试任务 #${created.id} 已创建并入队`);
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      form.resetFields();
      onClose();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="创建测试任务(MOCK)"
      okText="创建并入队"
      cancelText="取消"
      confirmLoading={submitting}
      onCancel={onClose}
      onOk={onOk}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary" className="mb-4">
        测试任务会按设定的步数/节奏空转,用于验证任务系统的状态机、并发上限、停止与重试。
      </Typography.Paragraph>
      <Form
        form={form}
        layout="vertical"
        initialValues={{ steps: 8, sleepMs: 600 }}
      >
        <Form.Item name="projectId" label="关联作品" rules={[{ required: true, message: '请选择作品' }]}>
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="选择作品"
            options={(projects?.records ?? []).map((p) => ({ value: p.id, label: p.title }))}
          />
        </Form.Item>
        <Space size="large">
          <Form.Item name="steps" label="步数(1-50)" rules={[{ required: true }]}>
            <InputNumber min={1} max={50} style={{ width: 120 }} />
          </Form.Item>
          <Form.Item name="sleepMs" label="每步耗时(ms)" rules={[{ required: true }]}>
            <InputNumber min={50} max={10000} step={100} style={{ width: 140 }} />
          </Form.Item>
        </Space>
        <Form.Item name="failAt" label="失败步骤(可选,用于模拟部分失败)">
          <Select
            mode="tags"
            open={false}
            placeholder="如: 2,4 表示第2/4步失败"
            tokenSeparators={[',', ' ']}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}

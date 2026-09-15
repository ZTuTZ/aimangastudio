import { Button, Card, Col, Row, Select, Space, Table, Tag, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App } from 'antd';
import { useState } from 'react';
import { adminApi } from '@/api/admin';
import { TASK_STATUS, TASK_TYPE_LABELS, tasksApi, type TaskVO } from '@/api/tasks';

const STAGE_NAMES: Record<string, string> = {
  SHEET: '角色设定表',
  REFERENCE: '素材参考图',
  LAYOUT: '布局图',
  IMAGE: '成品页',
};

const STAGE_STATUS: Record<number, { label: string; color: string }> = {
  0: { label: '排队', color: 'default' },
  1: { label: '进行中', color: 'processing' },
  2: { label: '完成', color: 'success' },
  3: { label: '失败', color: 'error' },
  4: { label: '暂停', color: 'warning' },
  5: { label: '停止', color: 'default' },
};

/** 全局任务监控(Phase 7.2,仅 ADMIN):系统概览 + 活跃项目阶段聚合 + 全用户任务表 */
export function TaskMonitor() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState<number | undefined>(undefined);
  const [type, setType] = useState<string | undefined>(undefined);

  const { data: overview } = useQuery({
    queryKey: ['monitor-overview'],
    queryFn: adminApi.monitorOverview,
    refetchInterval: 5000,
  });
  const { data: activeProjects } = useQuery({
    queryKey: ['monitor-active'],
    queryFn: adminApi.monitorActiveProjects,
    refetchInterval: 4000,
  });
  const { data: taskPage, isLoading } = useQuery({
    queryKey: ['monitor-tasks', page, status, type],
    queryFn: () => tasksApi.list({ page, size: 10, status, type }),
  });

  const act = useMutation({
    mutationFn: ({ id, action }: { id: number; action: 'stop' | 'retry' }) =>
      action === 'stop' ? tasksApi.stop(id) : tasksApi.retry(id),
    onSuccess: (_d, v) => {
      message.success(v.action === 'stop' ? '已停止' : '已重新入队');
      queryClient.invalidateQueries({ queryKey: ['monitor-tasks'] });
      queryClient.invalidateQueries({ queryKey: ['monitor-overview'] });
      queryClient.invalidateQueries({ queryKey: ['monitor-active'] });
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '操作失败'),
  });

  return (
    <div className="flex flex-col gap-4">
      <Row gutter={12}>
        <Col span={5}><StatCard title="进行中任务" value={overview?.runningTasks} /></Col>
        <Col span={5}><StatCard title="排队任务" value={overview?.pendingTasks} /></Col>
        <Col span={5}><StatCard title="队列长度" value={overview?.queueLength} /></Col>
        <Col span={9}>
          <Card size="small" title="AI 通道占用">
            <Space wrap size="large">
              {(overview?.aiChannels ?? []).map((c) => (
                <Typography.Text key={c.channel} className="text-sm">
                  {c.channel === 'text' ? '文本' : c.channel === 'image' ? '生图' : '编辑/合并'}
                  <Tag color={c.used >= c.total ? 'red' : 'processing'} bordered={false} className="ml-1">
                    {c.used}/{c.total}
                  </Tag>
                </Typography.Text>
              ))}
            </Space>
          </Card>
        </Col>
      </Row>

      <Card size="small" title="进行中的生成(按项目聚合)"
        extra={<Button size="small" icon={<ReloadOutlined />} onClick={() => {
          queryClient.invalidateQueries({ queryKey: ['monitor-active'] });
          queryClient.invalidateQueries({ queryKey: ['monitor-overview'] });
        }}>刷新</Button>}>
        {(!activeProjects || activeProjects.length === 0) ? (
          <Typography.Text type="secondary" className="text-sm">当前没有进行中的生成任务</Typography.Text>
        ) : (
          <div className="flex flex-col gap-3">
            {activeProjects.map((p) => (
              <div key={p.projectId} className="p-3 rounded-lg border border-gray-200 bg-white">
                <div className="flex items-center gap-2 mb-2">
                  <Typography.Text strong>{p.projectTitle}</Typography.Text>
                  <Tag bordered={false}>{TASK_TYPE_LABELS[p.taskType] ?? p.taskType} #{p.taskId}</Tag>
                  <Tag color={TASK_STATUS[p.taskStatus]?.color} bordered={false}>
                    {TASK_STATUS[p.taskStatus]?.label ?? p.taskStatus}
                  </Tag>
                </div>
                <div className="flex flex-wrap gap-3">
                  {p.stages.length === 0 && (
                    <Typography.Text type="secondary" className="text-xs">尚无阶段统计数据</Typography.Text>
                  )}
                  {p.stages.map((s) => {
                    const meta = STAGE_STATUS[s.status ?? 0] ?? STAGE_STATUS[0];
                    return (
                      <div key={s.stageType} className="px-3 py-2 rounded-lg border border-gray-100 bg-gray-50 text-center min-w-32">
                        <Typography.Text type="secondary" className="text-xs">{STAGE_NAMES[s.stageType] ?? s.stageType}</Typography.Text>
                        <div className="text-lg font-semibold">
                          {s.success ?? 0} <span className="text-xs text-gray-400">/ {s.total ?? 0}</span>
                        </div>
                        {(s.failed ?? 0) > 0 && <Typography.Text type="danger" className="text-xs">失败 {s.failed}</Typography.Text>}
                        <div className="mt-1"><Tag color={meta.color} bordered={false}>{meta.label}</Tag></div>
                      </div>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card size="small" title="全部任务">
        <Space className="mb-3" wrap>
          <Select
            allowClear placeholder="状态"
            style={{ width: 120 }}
            value={status}
            onChange={(v) => { setStatus(v ?? undefined); setPage(1); }}
            options={Object.entries(TASK_STATUS).map(([v, m]) => ({ value: Number(v), label: m.label }))}
          />
          <Select
            allowClear placeholder="类型"
            style={{ width: 140 }}
            value={type}
            onChange={(v) => { setType(v ?? undefined); setPage(1); }}
            options={Object.entries(TASK_TYPE_LABELS).map(([v, label]) => ({ value: v, label }))}
          />
        </Space>
        <Table<TaskVO>
          rowKey="id"
          size="small"
          loading={isLoading}
          dataSource={taskPage?.records ?? []}
          pagination={{
            current: page, pageSize: 10, total: taskPage?.total ?? 0,
            onChange: (p) => setPage(p), showSizeChanger: false,
          }}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: '用户', dataIndex: 'userId', width: 70 },
            { title: '作品', dataIndex: 'projectTitle', ellipsis: true },
            { title: '类型', dataIndex: 'taskType', width: 100, render: (t) => <Tag bordered={false}>{TASK_TYPE_LABELS[t] ?? t}</Tag> },
            { title: '状态', dataIndex: 'status', width: 90, render: (s) => <Tag color={TASK_STATUS[s]?.color} bordered={false}>{TASK_STATUS[s]?.label ?? s}</Tag> },
            { title: '进度', width: 150, render: (_, t) => `${t.progress}% (${t.successCount}/${t.totalCount})` },
            { title: '重试', dataIndex: 'retryCount', width: 60 },
            { title: '最后错误', dataIndex: 'lastError', ellipsis: true, render: (e) => e ? <Typography.Text type="danger" className="text-xs">{e}</Typography.Text> : '-' },
            {
              title: '操作', width: 140,
              render: (_, t) => [0, 1, 6].includes(t.status)
                ? <Button size="small" danger onClick={() => act.mutate({ id: t.id, action: 'stop' })}>停止</Button>
                : <Button size="small" onClick={() => act.mutate({ id: t.id, action: 'retry' })}>重试</Button>,
            },
          ]}
        />
      </Card>
    </div>
  );
}

function StatCard({ title, value }: { title: string; value: number | undefined }) {
  return (
    <Card size="small" title={title}>
      <Typography.Title level={3} style={{ margin: 0 }}>{value ?? '-'}</Typography.Title>
    </Card>
  );
}

import { Alert, Button, Table, Tag, Typography } from 'antd';
import { DownloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useMutation, useQuery } from '@tanstack/react-query';
import { App } from 'antd';
import { useState } from 'react';
import { adminApi, type AdminProjectRow, type BatchExportReport } from '@/api/admin';
import { tasksApi, type TaskVO } from '@/api/tasks';

const STATUS_META: Record<number, { label: string; color: string }> = {
  0: { label: '准备中', color: 'cyan' },
  1: { label: '待出图', color: 'blue' },
  2: { label: '出图中', color: 'geekblue' },
  3: { label: '完成', color: 'green' },
  4: { label: '部分失败', color: 'orange' },
};

/** 发布导出(Phase 7.5,仅 ADMIN):勾选作品 → 批量校验 → 批量导出(失败不阻塞,summary 随包返回) */
export function PublishExport() {
  const { message } = App.useApp();
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [report, setReport] = useState<BatchExportReport | null>(null);
  const [exportTaskId, setExportTaskId] = useState<number | null>(null);

  const { data: projects, isLoading } = useQuery({
    queryKey: ['admin-projects'],
    queryFn: adminApi.listAllProjects,
  });

  const validate = useMutation({
    mutationFn: () => adminApi.batchValidate(selectedIds),
    onSuccess: (r) => {
      setReport(r);
      message.success(`校验完成:${r.success} 部可导出,${r.failed} 部未通过`);
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '校验失败'),
  });

  const createExport = useMutation({
    mutationFn: () => adminApi.createBatchExport(selectedIds),
    onSuccess: (task) => {
      setExportTaskId(task.id);
      message.success(`导出任务 #${task.id} 已创建，可离开页面后稍后下载`);
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '创建导出任务失败'),
  });
  const { data: exportTask } = useQuery({
    queryKey: ['export-task', exportTaskId],
    queryFn: () => tasksApi.get(exportTaskId!),
    enabled: exportTaskId != null,
    refetchInterval: (query) => {
      const status = (query.state.data as TaskVO | undefined)?.status;
      return status != null && [2, 3, 4, 5].includes(status) ? false : 2000;
    },
  });
  const exportResult = parseExportResult(exportTask?.result);
  const downloadExport = useMutation({
    mutationFn: async () => {
      if (!exportResult?.artifactId) throw new Error('导出产物不存在');
      const blob = await adminApi.downloadBatchExport(exportResult.artifactId);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `aimanga-export-${exportTaskId}.zip`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '下载失败'),
  });

  return (
    <div className="flex flex-col gap-4">
      <CardTitle />
      <div className="flex items-center gap-3">
        <Typography.Text type="secondary" className="text-sm">
          已选 {selectedIds.length} 部 · 导出包 = manifest.json(comic-content-1.0) + 全部成品页图片
        </Typography.Text>
        <div className="flex-1" />
        <Button
          icon={<SafetyCertificateOutlined />}
          disabled={selectedIds.length === 0}
          loading={validate.isPending}
          onClick={() => validate.mutate()}
        >
          批量校验
        </Button>
        <Button
          type="primary"
          icon={<DownloadOutlined />}
          disabled={selectedIds.length === 0}
          loading={createExport.isPending || (exportTask != null && [0, 1, 6].includes(exportTask.status))}
          onClick={() => createExport.mutate()}
        >
          批量导出({selectedIds.length})
        </Button>
      </div>

      {exportTask && (
        <Alert
          type={exportTask.status === 2 ? 'success' : exportTask.status === 4 ? 'warning'
            : exportTask.status === 3 ? 'error' : 'info'}
          message={`导出任务 #${exportTask.id} · ${exportTask.progress ?? 0}%`}
          description={
            <div className="flex flex-col gap-2">
              <span>成功 {exportResult?.success ?? exportTask.successCount ?? 0} 部，失败 {exportResult?.failed ?? exportTask.failCount ?? 0} 部</span>
              {exportResult?.artifactId && exportTask.status != null && [2, 4].includes(exportTask.status) && (
                <Button type="link" className="self-start p-0" loading={downloadExport.isPending}
                  onClick={() => downloadExport.mutate()}>
                  下载批量导出包（含 summary.json，有效期至 {exportResult.expiresAt ?? '未知'}）
                </Button>
              )}
              {(exportResult?.items ?? []).filter((item) => !item.exported).map((item) => (
                <Typography.Text type="danger" key={item.projectId}>作品 #{item.projectId}：{item.error ?? '导出失败'}</Typography.Text>
              ))}
            </div>
          }
        />
      )}

      {report && (
        <Alert
          type={report.failed === 0 ? 'success' : 'warning'}
          message={`共 ${report.total} 部:可导出 ${report.success},未通过 ${report.failed}`}
          description={
            <div className="max-h-48 overflow-auto flex flex-col gap-1 text-xs">
              {report.items.filter((i) => i.valid === false).map((i) => (
                <div key={i.projectId}>
                  <Typography.Text type="danger">作品 #{i.projectId}</Typography.Text>
                  {(i.issues ?? []).filter((x) => x.level === 'ERROR').map((x, idx) => (
                    <span key={idx} className="ml-2">{x.message};</span>
                  ))}
                </div>
              ))}
            </div>
          }
        />
      )}

      <Table<AdminProjectRow>
        rowKey="id"
        size="small"
        loading={isLoading}
        dataSource={projects ?? []}
        pagination={{ pageSize: 10 }}
        rowSelection={{
          selectedRowKeys: selectedIds,
          onChange: (keys) => setSelectedIds(keys as number[]),
        }}
        columns={[
          { title: 'ID', dataIndex: 'id', width: 70 },
          { title: '作品', dataIndex: 'title', ellipsis: true },
          { title: '状态', dataIndex: 'status', width: 100, render: (s) => <Tag color={STATUS_META[s]?.color} bordered={false}>{STATUS_META[s]?.label ?? s}</Tag> },
          { title: 'content_uid', dataIndex: 'contentUid', ellipsis: true, render: (v) => <Typography.Text copyable className="text-xs">{v}</Typography.Text> },
        ]}
      />
    </div>
  );
}

interface ExportResult {
  artifactId?: number;
  downloadUrl?: string;
  expiresAt?: string;
  success?: number;
  failed?: number;
  items?: Array<{ projectId: number; exported: boolean; error?: string }>;
}

export function parseExportResult(value: string | null | undefined): ExportResult | null {
  if (!value) return null;
  try { return JSON.parse(value) as ExportResult; } catch { return null; }
}

function CardTitle() {
  return (
    <Typography.Title level={4} style={{ margin: 0 }}>
      发布导出
      <Typography.Text type="secondary" className="text-sm ml-3">
        校验会检查话序/页号连续性与成品图就绪情况;设定表/参考图不是发布必需项
      </Typography.Text>
    </Typography.Title>
  );
}

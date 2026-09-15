import { Alert, Button, Table, Tag, Typography } from 'antd';
import { DownloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useMutation, useQuery } from '@tanstack/react-query';
import { App } from 'antd';
import { useState } from 'react';
import { adminApi, type AdminProjectRow, type BatchExportReport } from '@/api/admin';
import { http } from '@/api/http';
import axios from 'axios';

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
  const [exporting, setExporting] = useState(false);

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

  const download = async () => {
    setExporting(true);
    try {
      // blob 下载(带鉴权 header,不能用 window.open)
      const response = await http.post('/admin/export/batch', { projectIds: selectedIds }, {
        responseType: 'blob',
        timeout: 600000,
      });
      const blob = new Blob([response.data as unknown as BlobPart], { type: 'application/zip' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `comic-batch-${Date.now()}.zip`;
      a.click();
      URL.revokeObjectURL(url);
      message.success('批量导出包已下载(包内含 summary.json 结果清单)');
    } catch (e) {
      // blob 响应里的业务错误(JSON)解析为可读消息
      let msg = e instanceof Error ? e.message : '导出失败';
      if (axios.isAxiosError(e) && e.response?.data instanceof Blob) {
        try {
          const text = await e.response.data.text();
          msg = JSON.parse(text).message ?? msg;
        } catch { /* 保留原始消息 */ }
      }
      message.error(msg);
    } finally {
      setExporting(false);
    }
  };

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
          loading={exporting}
          onClick={download}
        >
          批量导出({selectedIds.length})
        </Button>
      </div>

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

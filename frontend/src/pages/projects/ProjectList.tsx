import { App, Button, Empty, Input, Popconfirm, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { SearchOutlined, UploadOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { projectsApi, PROJECT_STATUS, type ProjectVO } from '@/api/projects';
import { UploadStoriesModal } from '@/components/UploadStoriesModal';
import type { ColumnsType } from 'antd/es/table';

/** 搜索条件(点击查询/回车才生效,避免每次击键都打接口) */
interface AppliedFilter {
  keyword?: string;
  status?: number;
}

export function ProjectList() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [uploadOpen, setUploadOpen] = useState(false);
  const [selectedIds, setSelectedIds] = useState<React.Key[]>([]);

  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(12);
  const [keywordInput, setKeywordInput] = useState('');
  const [statusInput, setStatusInput] = useState<number | undefined>();
  const [applied, setApplied] = useState<AppliedFilter>({});

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['projects', page, pageSize, applied],
    queryFn: () => projectsApi.list({ page, size: pageSize, ...applied }),
    placeholderData: (prev) => prev,
  });

  const applySearch = () => {
    setPage(1);
    setApplied({ keyword: keywordInput.trim() || undefined, status: statusInput });
  };

  const resetSearch = () => {
    setKeywordInput('');
    setStatusInput(undefined);
    setPage(1);
    setApplied({});
  };

  const onDelete = async (project: ProjectVO) => {
    try {
      await projectsApi.remove(project.id);
      message.success(`已删除「${project.title}」`);
      queryClient.invalidateQueries({ queryKey: ['projects'] });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败');
    }
  };

  const onBatchDelete = async () => {
    const ids = [...selectedIds];
    let ok = 0;
    let fail = 0;
    await Promise.all(
      ids.map(async (id) => {
        try {
          await projectsApi.remove(Number(id));
          ok += 1;
        } catch {
          fail += 1;
        }
      }),
    );
    if (fail === 0) {
      message.success(`已删除 ${ok} 部作品`);
    } else {
      message.warning(`已删除 ${ok} 部,${fail} 部失败(可能名下有生成中任务)`);
    }
    setSelectedIds([]);
    queryClient.invalidateQueries({ queryKey: ['projects'] });
  };

  const columns: ColumnsType<ProjectVO> = [
    {
      title: '作品名称',
      dataIndex: 'title',
      width: 280,
      render: (_, row) => (
        <div className="flex items-center gap-3 min-w-0">
          <div className="studio-table-cover">{row.coverUrl ? <img src={row.coverUrl} alt="" /> : row.title.slice(0, 1)}</div>
          <Tooltip title={row.title}>
            <Typography.Text strong ellipsis className="block max-w-[185px]">{row.title}</Typography.Text>
          </Tooltip>
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (s: number) => {
        const meta = PROJECT_STATUS[s] ?? { label: '未知', color: 'default' };
        return (
          <Tag color={meta.color} bordered={false} style={{ marginRight: 0 }}>
            {meta.label}
          </Tag>
        );
      },
    },
    { title: '画幅', dataIndex: 'aspectRatio', width: 80 },
    {
      title: '色彩模式',
      dataIndex: 'colorMode',
      width: 100,
      render: (m: string) => (m === 'partial' ? '局部上色' : m === 'monochrome' ? '黑白' : m === 'color' ? '全彩' : m),
    },
    {
      title: '话 / 页',
      key: 'scale',
      width: 90,
      render: (_, row) => `${row.chapterCount} 话 / ${row.pageCount} 页`,
    },
    { title: '创建时间', dataIndex: 'createTime', width: 170 },
    {
      title: '操作',
      key: 'actions',
      width: 110,
      render: (_, row) => (
        <Space size={4}>
          <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={(e) => { e.stopPropagation(); navigate(`/projects/${row.id}`); }}>
            打开
          </Button>
          <Popconfirm
            title={`删除「${row.title}」?`}
            description="话/页/资产将一并删除,不可恢复"
            okText="删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={(e) => { e?.stopPropagation(); onDelete(row); }}
            onCancel={(e) => e?.stopPropagation()}
          >
            <Button type="link" size="small" danger style={{ paddingInline: 4 }} onClick={(e) => e.stopPropagation()}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className="studio-page">
      <section className="studio-hero">
        <div>
          <span className="studio-page-kicker">YOUR CREATIVE SPACE</span>
          <h1>让故事，逐页成形。</h1>
          <p>从剧本到漫画，接着完成你正在创作的作品。</p>
        </div>
        <Button type="primary" size="large" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>上传故事</Button>
      </section>

      {page === 1 && !applied.keyword && applied.status === undefined && !!data?.records?.length && (
        <section>
          <div className="studio-section-heading"><h2>快速进入</h2><span className="studio-section-caption">继续你正在进行的故事</span></div>
          <div className="studio-project-featured">
            {data.records.slice(0, 3).map((project, index) => {
              const meta = PROJECT_STATUS[project.status] ?? { label: '未知', color: 'default' };
              return (
                <div key={project.id} className="studio-project-tile" role="button" tabIndex={0}
                  onClick={() => navigate(`/projects/${project.id}`)}
                  onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); navigate(`/projects/${project.id}`); } }}>
                  <div className={`studio-project-cover ${index === 1 ? 'is-second' : index === 2 ? 'is-third' : ''}`}>
                    {project.coverUrl ? <img src={project.coverUrl} alt="" /> : project.title.slice(0, 1)}
                  </div>
                  <div className="studio-project-info">
                    <strong title={project.title}>{project.title}</strong>
                    <Tag color={meta.color} bordered={false}>{meta.label}</Tag>
                    <small>{project.chapterCount} 话 · {project.pageCount} 页</small>
                    <span className="studio-project-more">继续创作 ↗</span>
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      )}

      <section className="studio-panel">
        <div className="studio-section-heading"><h2>全部作品</h2><span className="studio-section-caption">搜索、筛选和批量管理</span></div>
        <div className="studio-toolbar">
          <Input
            allowClear
            placeholder="按作品名称搜索"
            prefix={<SearchOutlined />}
            style={{ width: 240 }}
            value={keywordInput}
            onChange={(e) => setKeywordInput(e.target.value)}
            onPressEnter={applySearch}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 140 }}
            value={statusInput}
            onChange={(v) => setStatusInput(v)}
            options={Object.entries(PROJECT_STATUS).map(([v, m]) => ({ value: Number(v), label: m.label }))}
          />
          <Button type="primary" onClick={applySearch}>
            查询
          </Button>
          <Button onClick={resetSearch}>重置</Button>
          {selectedIds.length > 0 && (
            <>
              <Typography.Text type="secondary">已选 {selectedIds.length} 部</Typography.Text>
              <Popconfirm
                title={`批量删除 ${selectedIds.length} 部作品?`}
                description="话/页/资产将一并删除,不可恢复"
                okText="全部删除"
                okButtonProps={{ danger: true }}
                cancelText="取消"
                onConfirm={onBatchDelete}
              >
                <Button danger size="small">
                  批量删除
                </Button>
              </Popconfirm>
              <Button size="small" type="text" onClick={() => setSelectedIds([])}>
                取消选择
              </Button>
            </>
          )}
        </div>

        <Table<ProjectVO>
          rowKey="id"
          columns={columns}
          dataSource={data?.records ?? []}
          loading={isLoading || isFetching}
          locale={{ emptyText: <Empty description="暂无作品,点击右上角「上传故事」开始创作" /> }}
          onRow={(row) => ({ onClick: () => navigate(`/projects/${row.id}`), style: { cursor: 'pointer' } })}
          rowSelection={{
            selectedRowKeys: selectedIds,
            onChange: (keys) => setSelectedIds(keys),
            selections: [Table.SELECTION_ALL, Table.SELECTION_INVERT, Table.SELECTION_NONE],
          }}
          pagination={{
            current: page,
            pageSize,
            total: data?.total ?? 0,
            showSizeChanger: true,
            pageSizeOptions: [12, 24, 48],
            showTotal: (total) => `共 ${total} 部作品`,
            onChange: (p, s) => {
              setPage(p);
              setPageSize(s);
              setSelectedIds([]);
            },
          }}
        />
      </section>

      <UploadStoriesModal open={uploadOpen} onClose={() => setUploadOpen(false)} />
    </div>
  );
}

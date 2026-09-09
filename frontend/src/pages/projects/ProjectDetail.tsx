import { App, Button, Card, Descriptions, Form, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { ArrowLeftOutlined, PlusOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ComingSoon } from '@/components/ComingSoon';
import {
  ASSET_TYPE_NAMES,
  projectsApi,
  type AssetVO,
  type ChapterVO,
} from '@/api/projects';

export function ProjectDetail() {
  const { id } = useParams();
  const projectId = Number(id);
  const navigate = useNavigate();

  const { data: project } = useQuery({ queryKey: ['project', projectId], queryFn: () => projectsApi.get(projectId) });
  const { data: chapters } = useQuery({ queryKey: ['chapters', projectId], queryFn: () => projectsApi.chapters(projectId) });
  const { data: assets } = useQuery({ queryKey: ['assets', projectId], queryFn: () => projectsApi.assets(projectId) });
  const { data: presets } = useQuery({ queryKey: ['presets'], queryFn: projectsApi.presets });

  const [chapterModal, setChapterModal] = useState<{ open: boolean; chapter?: ChapterVO }>({ open: false });
  const [assetModal, setAssetModal] = useState<{ open: boolean; asset?: AssetVO }>({ open: false });

  if (!project) {
    return <Card loading />;
  }

  return (
    <div className="flex flex-col gap-4">
      <Card styles={{ body: { padding: 20 } }}>
        <div className="flex items-center justify-between">
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/')} />
            <div>
              <Typography.Title level={4} style={{ margin: 0 }}>
                {project.title}
              </Typography.Title>
              <Typography.Text type="secondary">{project.tagline || '暂无简介'}</Typography.Text>
            </div>
          </Space>
          <Space>
            <PresetSelect projectId={projectId} value={project.stylePresetId} presets={presets ?? []} />
          </Space>
        </div>
        <Descriptions
          size="small"
          className="mt-4"
          column={4}
          items={[
            { key: 'status', label: '状态', children: <ProjectStatusTag status={project.status} /> },
            { key: 'aspect', label: '画幅', children: project.aspectRatio },
            { key: 'color', label: '色彩模式', children: <ColorModeTag mode={project.colorMode} /> },
            { key: 'counts', label: '规模', children: `${project.chapterCount} 话 / ${project.pageCount} 页` },
          ]}
        />
      </Card>

      <Card title="话 / 章节(第一步流水线产出,Phase 5 上线)" styles={{ body: { paddingTop: 8 } }}>
        <Table<ChapterVO>
          rowKey="id"
          size="small"
          dataSource={chapters ?? []}
          pagination={false}
          locale={{ emptyText: '暂无话/章节 —— 上传作品后,任务系统将自动拆话并生成脚本(Phase 5)' }}
          columns={[
            { title: '话号', dataIndex: 'chapterNo', width: 70 },
            { title: '标题', dataIndex: 'title' },
            {
              title: '状态',
              dataIndex: 'status',
              width: 110,
              render: (s: number) =>
                s === 2 ? <Tag color="green">脚本就绪</Tag> : s === 0 ? <Tag>待处理</Tag> : <Tag color="blue">处理中</Tag>,
            },
            { title: '页数', dataIndex: 'pageCount', width: 70 },
            {
              title: '操作',
              width: 90,
              render: (_, row) => (
                <Button size="small" onClick={() => setChapterModal({ open: true, chapter: row })}>
                  编辑
                </Button>
              ),
            },
          ]}
        />
        <Button
          size="small"
          icon={<PlusOutlined />}
          className="mt-3"
          onClick={() => setChapterModal({ open: true })}
        >
          新增话
        </Button>
      </Card>

      <Card title="资产库(角色 / 场景 / 道具 / 服装)">
        <div className="flex flex-col gap-3">
          {[1, 2, 3, 4].map((type) => {
            const list = (assets ?? []).filter((a) => a.assetType === type);
            return (
              <div key={type}>
                <Typography.Text type="secondary" className="text-xs">
                  {ASSET_TYPE_NAMES[type]}({list.length})
                </Typography.Text>
                <div className="flex flex-wrap gap-2 mt-1">
                  {list.length === 0 && <Typography.Text type="secondary">—</Typography.Text>}
                  {list.map((asset) => (
                    <button
                      key={asset.id}
                      className="px-3 py-1.5 rounded-lg border border-gray-200 text-sm hover:border-indigo-400 hover:text-indigo-500"
                      onClick={() => setAssetModal({ open: true, asset })}
                    >
                      {asset.name}
                    </button>
                  ))}
                </div>
              </div>
            );
          })}
          <Button
            size="small"
            icon={<PlusOutlined />}
            className="mt-1"
            onClick={() => setAssetModal({ open: true })}
          >
            新增资产
          </Button>
        </div>
      </Card>

      <Card title="生成成品">
        <ComingSoon
          title=""
          items={['选择范围(整部剧/按话)与色彩模式,一键生成成品页(Phase 6)']}
        />
      </Card>

      <ChapterEditModal
        state={chapterModal}
        projectId={projectId}
        onClose={() => setChapterModal({ open: false })}
      />
      <AssetEditModal
        state={assetModal}
        projectId={projectId}
        onClose={() => setAssetModal({ open: false })}
      />
    </div>
  );
}

function ProjectStatusTag({ status }: { status: number }) {
  const map: Record<number, { label: string; color: string }> = {
    0: { label: '准备中', color: 'default' },
    1: { label: '待出图', color: 'blue' },
    2: { label: '出图中', color: 'processing' },
    3: { label: '完成', color: 'green' },
    4: { label: '部分失败', color: 'orange' },
  };
  const s = map[status] ?? { label: '未知', color: 'default' };
  return <Tag color={s.color}>{s.label}</Tag>;
}

function ColorModeTag({ mode }: { mode: string }) {
  const label = mode === 'partial' ? '局部上色' : mode === 'monochrome' ? '黑白' : mode === 'color' ? '全彩' : mode;
  return <Tag>{label}</Tag>;
}

function PresetSelect({
  projectId,
  value,
  presets,
}: {
  projectId: number;
  value: number | null;
  presets: { id: number; name: string }[];
}) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  return (
    <Select
      style={{ width: 200 }}
      placeholder="风格预设"
      allowClear
      value={value ?? undefined}
      onChange={async (v) => {
        try {
          await projectsApi.update(projectId, { stylePresetId: v ?? null });
          message.success('风格预设已更新');
          queryClient.invalidateQueries({ queryKey: ['project', projectId] });
        } catch (e) {
          message.error(e instanceof Error ? e.message : '更新失败');
        }
      }}
      options={presets.map((p) => ({ value: p.id, label: p.name }))}
    />
  );
}

function ChapterEditModal({
  state,
  projectId,
  onClose,
}: {
  state: { open: boolean; chapter?: ChapterVO };
  projectId: number;
  onClose: () => void;
}) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<{ title: string; scriptText?: string }>();
  const [saving, setSaving] = useState(false);

  const onOk = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (state.chapter) {
        await projectsApi.updateChapter(state.chapter.id, values);
        message.success('话已保存');
      } else {
        await projectsApi.createChapter(projectId, values);
        message.success('话已新增');
      }
      queryClient.invalidateQueries({ queryKey: ['chapters', projectId] });
      onClose();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open={state.open}
      title={state.chapter ? `编辑「${state.chapter.title}」` : '新增话'}
      okText="保存"
      cancelText="取消"
      confirmLoading={saving}
      onCancel={onClose}
      onOk={onOk}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={state.chapter ? { title: state.chapter.title, scriptText: state.chapter.scriptText ?? '' } : {}}
      >
        <Form.Item name="title" label="话标题" rules={[{ required: !state.chapter, message: '请输入标题' }]}>
          <Input placeholder="第1话 标题" />
        </Form.Item>
        <Form.Item name="scriptText" label="本话故事原文(修改后可在 Phase 5 重新生成脚本)">
          <Input.TextArea rows={6} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function AssetEditModal({
  state,
  projectId,
  onClose,
}: {
  state: { open: boolean; asset?: AssetVO };
  projectId: number;
  onClose: () => void;
}) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<{
    assetType: number;
    name: string;
    aliases?: string;
    description?: string;
    structured?: string;
    referenceUrl?: string;
  }>();
  const [saving, setSaving] = useState(false);

  const onOk = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (state.asset) {
        await projectsApi.updateAsset(state.asset.id, values);
        message.success('资产已保存');
      } else {
        await projectsApi.createAsset(projectId, values);
        message.success('资产已新增');
      }
      queryClient.invalidateQueries({ queryKey: ['assets', projectId] });
      onClose();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open={state.open}
      title={state.asset ? `编辑资产「${state.asset.name}」` : '新增资产'}
      okText="保存"
      cancelText="取消"
      confirmLoading={saving}
      onCancel={onClose}
      onOk={onOk}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={
          state.asset
            ? {
                assetType: state.asset.assetType,
                name: state.asset.name,
                aliases: state.asset.aliases ?? '',
                description: state.asset.description ?? '',
                structured: state.asset.structured ?? '',
                referenceUrl: state.asset.referenceUrl ?? '',
              }
            : { assetType: 1 }
        }
      >
        <Space className="w-full" size="large">
          <Form.Item name="assetType" label="类型" rules={[{ required: true }]}>
            <Select
              style={{ width: 120 }}
              options={Object.entries(ASSET_TYPE_NAMES).map(([v, label]) => ({ value: Number(v), label }))}
            />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="角色/场景名" style={{ width: 260 }} />
          </Form.Item>
        </Space>
        <Form.Item name="aliases" label="别名(逗号分隔)">
          <Input placeholder="别名1,别名2" />
        </Form.Item>
        <Form.Item name="description" label="描述">
          <Input.TextArea rows={3} placeholder="外观/性格/用途描述,生成脚本与设定表时会使用" />
        </Form.Item>
        <Form.Item
          name="structured"
          label="结构化设定(JSON,角色建议 {role,age,hair,accessories,top,bottom})"
          rules={[
            {
              validator: (_, value) => {
                if (!value) return Promise.resolve();
                try {
                  JSON.parse(value);
                  return Promise.resolve();
                } catch {
                  return Promise.reject(new Error('不是合法 JSON'));
                }
              },
            },
          ]}
        >
          <Input.TextArea rows={3} placeholder='{"role":"男主","age":"18","hair":"黑色短发"}' />
        </Form.Item>
        <Form.Item name="referenceUrl" label="参考图 URL(可先经 系统配置→文件上传 获取)">
          <Input placeholder="https://…" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

import { App, Button, Card, Descriptions, Form, Input, Modal, Segmented, Select, Space, Table, Tabs, Tag, Typography } from 'antd';
import { ArrowLeftOutlined, PlusOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ComingSoon } from '@/components/ComingSoon';
import {
  ASSET_TYPE_NAMES,
  PROJECT_STATUS,
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
  const [assetCategory, setAssetCategory] = useState<number>(1); // 默认展示「角色」

  if (!project) {
    return <Card loading />;
  }

  const chapterCount = chapters?.length ?? 0;
  const assetCount = assets?.length ?? 0;

  return (
    <div className="flex flex-col gap-4">
      {/* 基本信息(保持原状) */}
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

      {/* 三个工作区改为 Tab */}
      <Card styles={{ body: { paddingTop: 4 } }}>
        <Tabs
          defaultActiveKey="chapters"
          items={[
            {
              key: 'chapters',
              label: `话 / 章节 (${chapterCount})`,
              children: <ChaptersTab chapters={chapters ?? []} onEdit={(chapter) => setChapterModal({ open: true, chapter })} onAdd={() => setChapterModal({ open: true })} />,
            },
            {
              key: 'assets',
              label: `资产库 (${assetCount})`,
              children: (
                <AssetsTab
                  assets={assets ?? []}
                  category={assetCategory}
                  onCategoryChange={setAssetCategory}
                  onEdit={(asset) => setAssetModal({ open: true, asset })}
                  onAdd={() => setAssetModal({ open: true })}
                />
              ),
            },
            {
              key: 'generate',
              label: '生成成品',
              children: <ComingSoon title="" items={['选择范围(整部剧/按话)与色彩模式,一键生成成品页(Phase 6)']} />,
            },
          ]}
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
        defaultType={assetCategory}
        onClose={() => setAssetModal({ open: false })}
      />
    </div>
  );
}

/** 话 / 章节 Tab */
function ChaptersTab({
  chapters,
  onEdit,
  onAdd,
}: {
  chapters: ChapterVO[];
  onEdit: (chapter: ChapterVO) => void;
  onAdd: () => void;
}) {
  return (
    <div>
      <Table<ChapterVO>
        rowKey="id"
        size="small"
        dataSource={chapters}
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
              s === 2 ? <Tag color="green" bordered={false}>脚本就绪</Tag> : s === 0 ? <Tag bordered={false}>待处理</Tag> : <Tag color="blue" bordered={false}>处理中</Tag>,
          },
          { title: '页数', dataIndex: 'pageCount', width: 70 },
          {
            title: '操作',
            width: 80,
            render: (_, row) => (
              <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={() => onEdit(row)}>
                编辑
              </Button>
            ),
          },
        ]}
      />
      <Button size="small" icon={<PlusOutlined />} className="mt-3" onClick={onAdd}>
        新增话
      </Button>
    </div>
  );
}

/** 资产库 Tab:四类分类标签,默认选中「角色」 */
function AssetsTab({
  assets,
  category,
  onCategoryChange,
  onEdit,
  onAdd,
}: {
  assets: AssetVO[];
  category: number;
  onCategoryChange: (v: number) => void;
  onEdit: (asset: AssetVO) => void;
  onAdd: () => void;
}) {
  const countOf = (type: number) => assets.filter((a) => a.assetType === type).length;
  const list = assets.filter((a) => a.assetType === category);

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <Segmented
          value={category}
          onChange={(v) => onCategoryChange(Number(v))}
          options={[1, 2, 3, 4].map((type) => ({
            value: type,
            label: `${ASSET_TYPE_NAMES[type]}(${countOf(type)})`,
          }))}
        />
        <Button size="small" icon={<PlusOutlined />} onClick={onAdd}>
          新增{ASSET_TYPE_NAMES[category]}
        </Button>
      </div>

      {list.length === 0 ? (
        <Typography.Text type="secondary">
          暂无{ASSET_TYPE_NAMES[category]}资产 —— 第一步流水线会自动从脚本中提取(Phase 5)
        </Typography.Text>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-3">
          {list.map((asset) => (
            <button
              key={asset.id}
              className="text-left p-3 rounded-xl border border-gray-200 hover:border-indigo-400 hover:shadow-sm transition bg-white"
              onClick={() => onEdit(asset)}
            >
              <div className="flex items-center gap-2">
                <Typography.Text strong ellipsis className="flex-1">
                  {asset.name}
                </Typography.Text>
                {asset.sheetImageUrl ? (
                  <Tag color="green" bordered={false} style={{ marginRight: 0 }}>
                    已有设定表
                  </Tag>
                ) : null}
              </div>
              {asset.description && (
                <Typography.Paragraph type="secondary" className="mt-1 mb-0 text-xs" ellipsis={{ rows: 2, tooltip: asset.description }}>
                  {asset.description}
                </Typography.Paragraph>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function ProjectStatusTag({ status }: { status: number }) {
  const meta = PROJECT_STATUS[status] ?? { label: '未知', color: 'default' };
  return (
    <Tag color={meta.color} bordered={false} style={{ marginRight: 0 }}>
      {meta.label}
    </Tag>
  );
}

function ColorModeTag({ mode }: { mode: string }) {
  const label = mode === 'partial' ? '局部上色' : mode === 'monochrome' ? '黑白' : mode === 'color' ? '全彩' : mode;
  return <Tag bordered={false}>{label}</Tag>;
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
  defaultType,
  onClose,
}: {
  state: { open: boolean; asset?: AssetVO };
  projectId: number;
  defaultType: number;
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
      title={state.asset ? `编辑资产「${state.asset.name}」` : `新增${ASSET_TYPE_NAMES[defaultType] ?? '资产'}`}
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
            : { assetType: defaultType }
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

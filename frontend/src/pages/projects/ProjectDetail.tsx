import { App, AutoComplete, Button, Card, Checkbox, Form, Image, Input, Modal, Popconfirm, Segmented, Select, Space, Table, Tabs, Tag, Tooltip, Typography, Upload } from 'antd';
import { ArrowLeftOutlined, EditOutlined, FileImageOutlined, PlusOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { GenerationWorkbench } from '@/components/GenerationWorkbench';
import {
  ASSET_TYPE_NAMES,
  CATEGORY_SUGGESTIONS,
  PROJECT_STATUS,
  SERIES_STATUS,
  projectsApi,
  type AssetVO,
  type ChapterVO,
  type PageVO,
  type ProjectVO,
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
  const [tabKey, setTabKey] = useState<string>('chapters');
  const [infoModalOpen, setInfoModalOpen] = useState(false);

  if (!project) {
    return <Card loading />;
  }

  const chapterCount = chapters?.length ?? 0;
  const assetCount = assets?.length ?? 0;
  const tagList: string[] = (() => {
    try {
      const parsed = JSON.parse(project.tags ?? '[]');
      return Array.isArray(parsed) ? parsed.filter((t) => typeof t === 'string') : [];
    } catch {
      return [];
    }
  })();

  return (
    <div className="flex flex-col gap-4">
      {/* 基本信息:封面 + 标题 + 状态/元数据 主视觉布局 */}
      <Card styles={{ body: { padding: 20 } }}>
        <div className="flex gap-5">
          <div className="w-24 h-32 rounded-xl overflow-hidden flex-shrink-0 shadow-sm">
            {project.coverUrl ? (
              <img src={project.coverUrl} alt={project.title} className="w-full h-full object-cover" />
            ) : (
              <div
                className="w-full h-full flex items-center justify-center text-white text-3xl font-bold"
                style={{ background: 'linear-gradient(135deg,#6366f1,#a855f7)' }}
              >
                {project.title.slice(0, 1)}
              </div>
            )}
          </div>
          <div className="flex-1 min-w-0 flex flex-col gap-1.5">
            <div className="flex items-start justify-between gap-3">
              <Typography.Title level={4} style={{ margin: 0 }} ellipsis={{ tooltip: project.title }}>
                {project.title}
              </Typography.Title>
              <Space className="flex-shrink-0">
                <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/')} title="返回作品库" />
                <PresetSelect projectId={projectId} value={project.stylePresetId} presets={presets ?? []} />
                <Button icon={<EditOutlined />} onClick={() => setInfoModalOpen(true)}>
                  编辑信息
                </Button>
              </Space>
            </div>
            {project.tagline && (
              <Typography.Text type="secondary" ellipsis={{ tooltip: project.tagline }}>
                {project.tagline}
              </Typography.Text>
            )}
            <Space size={6} wrap>
              <ProjectStatusTag status={project.status} />
              <SeriesStatusTag status={project.seriesStatus} />
              {project.category && <Tag bordered={false}>{project.category}</Tag>}
              <ColorModeTag mode={project.colorMode} />
              <Tag bordered={false}>{project.aspectRatio}</Tag>
            </Space>
            {tagList.length > 0 && (
              <Space size={4} wrap>
                {tagList.map((t) => (
                  <Tag key={t} bordered={false} style={{ marginRight: 0, background: '#eef2ff', color: '#6366f1' }}>
                    #{t}
                  </Tag>
                ))}
              </Space>
            )}
            <Tooltip title={`${project.contentUid}（点击复制）`}>
              <div>
                <Typography.Text
                  code
                  type="secondary"
                  copyable={{ text: project.contentUid, tooltips: ['复制内容 ID', '已复制'] }}
                  style={{ fontSize: 12, maxWidth: 420 }}
                  ellipsis
                >
                  {project.contentUid}
                </Typography.Text>
              </div>
            </Tooltip>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              创建于 {project.createTime} · {chapterCount} 话 / {project.pageCount} 页
            </Typography.Text>
          </div>
        </div>
      </Card>

      {/* 三个工作区改为 Tab */}
      <Card styles={{ body: { paddingTop: 4 } }}>
        <Tabs
          activeKey={tabKey}
          onChange={setTabKey}
          defaultActiveKey="chapters"
          items={[
            {
              key: 'chapters',
              label: `话 / 章节 (${chapterCount})`,
              children: (
                <ChaptersTab
                  projectId={projectId}
                  chapters={chapters ?? []}
                  onEdit={(chapter) => setChapterModal({ open: true, chapter })}
                  onAdd={() => setChapterModal({ open: true })}
                />
              ),
            },
            {
              key: 'assets',
              label: `资产库 (${assetCount})`,
              children: (
                <AssetsTab
                  projectId={projectId}
                  project={project}
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
              children: (
                <GenerationWorkbench
                  projectId={projectId}
                  project={project}
                  chapters={chapters ?? []}
                  onGoAssets={() => setTabKey('assets')}
                />
              ),
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

      <InfoEditModal
        open={infoModalOpen}
        project={project}
        onClose={() => setInfoModalOpen(false)}
      />
    </div>
  );
}

/** 作品元数据编辑:短简介/正式简介/封面/主分类/标签/连载状态(content_uid 不可编辑) */
function InfoEditModal({
  open,
  project,
  onClose,
}: {
  open: boolean;
  project: ProjectVO;
  onClose: () => void;
}) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<{
    tagline: string;
    description: string;
    coverUrl: string;
    category: string;
    tagList: string[];
    seriesStatus: number;
  }>();
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);

  const tagList: string[] = (() => {
    try {
      const parsed = JSON.parse(project.tags ?? '[]');
      return Array.isArray(parsed) ? parsed.filter((t) => typeof t === 'string') : [];
    } catch {
      return [];
    }
  })();

  const onOk = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await projectsApi.update(project.id, {
        tagline: values.tagline ?? '',
        description: values.description ?? '',
        coverUrl: values.coverUrl ?? '',
        category: values.category ?? '',
        tags: JSON.stringify(values.tagList ?? []),
        seriesStatus: values.seriesStatus,
      });
      message.success('作品信息已保存');
      queryClient.invalidateQueries({ queryKey: ['project', project.id] });
      queryClient.invalidateQueries({ queryKey: ['projects'] });
      onClose();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open={open}
      title="编辑作品信息"
      width={620}
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
        initialValues={{
          tagline: project.tagline ?? '',
          description: project.description ?? '',
          coverUrl: project.coverUrl ?? '',
          category: project.category ?? '',
          tagList,
          seriesStatus: project.seriesStatus ?? 2,
        }}
      >
        <Form.Item name="tagline" label="短简介(卡片一句话卖点,建议 20-50 字)">
          <Input placeholder="如:末日重临,他带着前世记忆抢占最后的生机" showCount maxLength={64} />
        </Form.Item>
        <Form.Item name="description" label="正式简介(详情页用,建议 80-300 字,避免剧透结局)">
          <Input.TextArea rows={4} maxLength={2000} showCount />
        </Form.Item>
        <Form.Item name="coverUrl" label="封面(可上传到 OSS 自动填充,也可直接粘贴 URL)">
          <Space.Compact style={{ width: '100%' }}>
            <Form.Item name="coverUrl" noStyle>
              <Input placeholder="https://…oss…/cover.png" />
            </Form.Item>
            <Upload
              accept="image/*"
              showUploadList={false}
              beforeUpload={async (file) => {
                setUploading(true);
                try {
                  const { url } = await projectsApi.uploadFile(file);
                  form.setFieldValue('coverUrl', url);
                  message.success('封面上传成功');
                } catch (e) {
                  message.error(e instanceof Error ? e.message : '上传失败');
                } finally {
                  setUploading(false);
                }
                return false;
              }}
            >
              <Button icon={<UploadOutlined />} loading={uploading}>
                上传封面
              </Button>
            </Upload>
          </Space.Compact>
        </Form.Item>
        <Space className="w-full" size="large">
          <Form.Item name="category" label="主分类" style={{ minWidth: 200 }}>
            <AutoComplete
              placeholder="选择或输入分类"
              options={CATEGORY_SUGGESTIONS.map((c) => ({ value: c }))}
              filterOption={(input, option) => (option?.value as string).includes(input)}
            />
          </Form.Item>
          <Form.Item name="seriesStatus" label="连载状态" rules={[{ required: true }]}>
            <Select
              style={{ width: 140 }}
              options={[
                { value: 1, label: '连载中' },
                { value: 2, label: '已完结' },
              ]}
            />
          </Form.Item>
        </Space>
        <Form.Item name="tagList" label="标签(3-8 个,回车添加)">
          <Select mode="tags" placeholder="如:重生 / 系统 / 异能" open={false} tokenSeparators={[',']} />
        </Form.Item>
        <Typography.Text type="secondary" className="text-xs">
          内容 ID({project.contentUid})由系统生成且不可修改,用于未来漫画平台导入对齐。
        </Typography.Text>
      </Form>
    </Modal>
  );
}

/** 话 / 章节 Tab:重拆话 + 展开页脚本编辑 + 单话重跑脚本 */
function ChaptersTab({ projectId, chapters, onEdit, onAdd }: {
  projectId: number;
  chapters: ChapterVO[];
  onEdit: (chapter: ChapterVO) => void;
  onAdd: () => void;
}) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();

  const runTask = async (action: () => Promise<unknown>, okText: string) => {
    try {
      await action();
      message.success(okText);
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
    }
  };

  return (
    <div>
      <div className="flex items-center gap-2 mb-3">
        <Button size="small" icon={<PlusOutlined />} onClick={onAdd}>
          新增话
        </Button>
        <Popconfirm
          title="重新拆话?"
          description="将按原文重新拆分并删除现有话记录(已生成的页内容会被清除)。生成中的任务不受影响。"
          okText="重新拆话"
          okButtonProps={{ danger: true }}
          cancelText="取消"
          onConfirm={async () => {
            try {
              await projectsApi.split(projectId);
              message.success('拆话任务已创建,可到任务中心查看进度');
              queryClient.invalidateQueries({ queryKey: ['tasks'] });
            } catch (e) {
              message.error(e instanceof Error ? e.message : '操作失败');
            }
          }}
        >
          <Button size="small">重新拆话</Button>
        </Popconfirm>
      </div>
      <Table<ChapterVO>
        rowKey="id"
        size="small"
        dataSource={chapters}
        pagination={false}
        expandable={{
          expandedRowRender: (chapter) => <PagesOfChapter chapterId={chapter.id} />,
          rowExpandable: (chapter) => chapter.pageCount > 0 || chapter.status >= 2,
        }}
        locale={{ emptyText: '暂无话/章节 —— 拆话任务完成后自动出现' }}
        columns={[
          { title: '话号', dataIndex: 'chapterNo', width: 70 },
          { title: '标题', dataIndex: 'title' },
          {
            title: '状态',
            dataIndex: 'status',
            width: 110,
            render: (s: number) =>
              s >= 2 ? <Tag color="green" bordered={false}>脚本就绪</Tag> : s === 0 ? <Tag bordered={false}>待处理</Tag> : <Tag color="blue" bordered={false}>处理中</Tag>,
          },
          { title: '页数', dataIndex: 'pageCount', width: 70 },
          {
            title: '操作',
            width: 170,
            render: (_, row) => (
              <Space size={0}>
                <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={() => onEdit(row)}>
                  编辑
                </Button>
                <Popconfirm title={`重新生成「${row.title}」的脚本?`} description="该话现有页与生成图将被重建" okText="重跑" cancelText="取消"
                  onConfirm={() => runTask(() => projectsApi.regenerateScript(row.id), '脚本生成任务已创建')}>
                  <Button type="link" size="small" style={{ paddingInline: 4 }}>
                    重跑脚本
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
    </div>
  );
}

/** 话内页脚本(只读概览 + 单页编辑) */
function PagesOfChapter({ chapterId }: { chapterId: number }) {
  const { data, isLoading } = useQuery({
    queryKey: ['pages', chapterId],
    queryFn: () => projectsApi.pages(chapterId),
  });
  const [editing, setEditing] = useState<PageVO | null>(null);

  return (
    <div className="pl-6 pr-2 pb-2">
      <Table<PageVO>
        rowKey="id"
        size="small"
        loading={isLoading}
        dataSource={data ?? []}
        pagination={false}
        locale={{ emptyText: '该话暂无页' }}
        columns={[
          { title: '页号', dataIndex: 'pageNo', width: 60 },
          {
            title: '旁白',
            dataIndex: 'narration',
            ellipsis: true,
            render: (v: string | null) => v || '—',
          },
          {
            title: '对白',
            key: 'dialogue',
            ellipsis: true,
            render: (_, row) => {
              try {
                const list = JSON.parse(row.dialogue ?? '[]');
                if (!Array.isArray(list) || list.length === 0) return '—';
                return list.map((d) => `${d.speaker ?? ''}：${d.line ?? ''}`).join(' / ');
              } catch {
                return '—';
              }
            },
          },
          {
            title: '画面',
            dataIndex: 'visual',
            ellipsis: true,
            render: (v: string | null) => v || '—',
          },
          {
            title: '操作',
            width: 70,
            render: (_, row) => (
              <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={() => setEditing(row)}>
                编辑
              </Button>
            ),
          },
        ]}
      />
      <PageEditModal page={editing} onClose={() => setEditing(null)} />
    </div>
  );
}

function PageEditModal({ page, onClose }: { page: PageVO | null; onClose: () => void }) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<{ narration: string; dialogueLines: string; visual: string }>();
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!page) return;
    let lines = '';
    try {
      const list = JSON.parse(page.dialogue ?? '[]');
      if (Array.isArray(list)) {
        lines = list.map((d) => `${d.speaker ?? ''}：${d.line ?? ''}`).join('\n');
      }
    } catch {
      lines = '';
    }
    form.setFieldsValue({
      narration: page.narration ?? '',
      dialogueLines: lines,
      visual: page.visual ?? '',
    });
  }, [page, form]);

  const onOk = async () => {
    if (!page) return;
    const values = await form.validateFields();
    const dialogue = (values.dialogueLines ?? '')
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.length > 0)
      .map((line) => {
        const idx = line.indexOf('：') >= 0 ? line.indexOf('：') : line.indexOf(':');
        return idx > 0
          ? { speaker: line.substring(0, idx).trim(), line: line.substring(idx + 1).trim() }
          : { speaker: '', line };
      });
    setSaving(true);
    try {
      await projectsApi.updatePage(page.id, {
        narration: values.narration ?? '',
        dialogue: JSON.stringify(dialogue),
        visual: values.visual ?? '',
      });
      message.success('页脚本已保存');
      queryClient.invalidateQueries({ queryKey: ['pages', page.chapterId] });
      onClose();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open={!!page}
      title={`编辑第 ${page?.pageNo ?? ''} 页脚本`}
      okText="保存"
      cancelText="取消"
      confirmLoading={saving}
      onCancel={onClose}
      onOk={onOk}
      width={640}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item name="narration" label="旁白(矩形叙述框)">
          <Input.TextArea rows={2} />
        </Form.Item>
        <Form.Item name="dialogueLines" label="对白(每行一条,格式: 角色名：台词)">
          <Input.TextArea rows={4} placeholder={'林夏：你回来了'} />
        </Form.Item>
        <Form.Item name="visual" label="画面详述(分镜构图+角色衣着特征)">
          <Input.TextArea rows={4} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

/** 素材参考图可选画幅(生图模型支持的三档) */
const RATIO_OPTIONS = [
  { value: '16:9', label: '16:9 横版' },
  { value: '3:4', label: '3:4 竖版' },
  { value: '1:1', label: '1:1 方形' },
];

/** 资产库 Tab:四类分类标签,默认选中「角色」;勾选资产批量生成素材图(角色→设定表,其他→参考图) */
function AssetsTab({ projectId, project, assets, category, onCategoryChange, onEdit, onAdd }: {
  projectId: number;
  project: ProjectVO;
  assets: AssetVO[];
  category: number;
  onCategoryChange: (v: number) => void;
  onEdit: (asset: AssetVO) => void;
  onAdd: () => void;
}) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const countOf = (type: number) => assets.filter((a) => a.assetType === type).length;
  const list = assets.filter((a) => a.assetType === category);
  const isCharacter = category === 1;
  const [selected, setSelected] = useState<Set<number>>(new Set());

  const toggle = (id: number, checked: boolean) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
  };

  const genSheet = async (asset: AssetVO) => {
    await generateBatch([asset.id]);
  };

  /** 批量生成素材图:角色→设定表任务,场景/道具/服装→参考图任务 */
  const generateBatch = async (ids: number[]) => {
    try {
      const tasks = await projectsApi.generateForAssets(projectId, ids);
      message.success(`素材生成任务已创建(${tasks.map((t) => `#${t.id}`).join('、')}),并发生成中`);
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      queryClient.invalidateQueries({ queryKey: ['assets', projectId] });
      setSelected(new Set());
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
    }
  };

  /** 修改素材参考图画幅配置(保存到作品,生成前可随时调整) */
  const updateRatio = async (field: 'sceneRatio' | 'propRatio' | 'costumeRatio', value: string) => {
    try {
      await projectsApi.update(projectId, { [field]: value });
      queryClient.invalidateQueries({ queryKey: ['project', projectId] });
      message.success('素材比例已保存');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    }
  };

  const rebuild = async () => {
    try {
      await projectsApi.rebuildAssets(projectId);
      message.success('资产提取任务已创建');
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      setSelected(new Set());
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
    }
  };

  const ratioSelect = (label: string, field: 'sceneRatio' | 'propRatio' | 'costumeRatio', value: string | null) => (
    <Space size={4}>
      <Typography.Text type="secondary" className="text-xs">{label}</Typography.Text>
      <Select
        size="small"
        style={{ width: 92 }}
        value={value ?? '1:1'}
        onChange={(v) => updateRatio(field, v)}
        options={RATIO_OPTIONS}
      />
    </Space>
  );

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-2 mb-4">
        <Segmented
          value={category}
          onChange={(v) => {
            setSelected(new Set());
            onCategoryChange(Number(v));
          }}
          options={[1, 2, 3, 4].map((type) => ({
            value: type,
            label: `${ASSET_TYPE_NAMES[type]}(${countOf(type)})`,
          }))}
        />
        <Space wrap>
          {!isCharacter && (
            <>
              {ratioSelect('场景比例', 'sceneRatio', project.sceneRatio)}
              {ratioSelect('道具比例', 'propRatio', project.propRatio)}
              {ratioSelect('服装比例', 'costumeRatio', project.costumeRatio)}
            </>
          )}
          {isCharacter && (
            <Typography.Text type="secondary" className="text-xs">设定表固定 3:4</Typography.Text>
          )}
          <Tooltip title="AI 从故事原文重新提取四类资产(同名跳过)">
            <Button size="small" icon={<ReloadOutlined />} onClick={rebuild}>
              AI 重新提取
            </Button>
          </Tooltip>
          <Button size="small" icon={<PlusOutlined />} onClick={onAdd}>
            新增{ASSET_TYPE_NAMES[category]}
          </Button>
        </Space>
      </div>

      {list.length > 0 && (
        <div className="flex items-center gap-2 mb-3 px-3 py-2 rounded-lg bg-indigo-50/60 border border-indigo-100">
          <Checkbox
            checked={selected.size > 0 && selected.size === list.length}
            indeterminate={selected.size > 0 && selected.size < list.length}
            onChange={(e) => setSelected(e.target.checked ? new Set(list.map((a) => a.id)) : new Set())}
          >
            <span className="text-sm">全选</span>
          </Checkbox>
          <Typography.Text type="secondary" className="text-xs">
            已选 {selected.size} 项{isCharacter ? ',将并发生成六姿势设定表' : ',将并发生成概念参考图'}
          </Typography.Text>
          <div className="flex-1" />
          <Button
            type="primary"
            size="small"
            disabled={selected.size === 0}
            onClick={() => generateBatch([...selected])}
          >
            {isCharacter ? '生成设定表' : '生成参考图'}{selected.size > 0 ? `(${selected.size})` : ''}
          </Button>
        </div>
      )}

      {list.length === 0 ? (
        <Typography.Text type="secondary">
          暂无{ASSET_TYPE_NAMES[category]}资产 —— 第一步流水线会自动从脚本中提取(Phase 5)
        </Typography.Text>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-3">
          {list.map((asset) => {
            // 图片来源优先级:角色设定表 > 参考图;点击可放大预览
            const imageUrl = asset.sheetImageUrl || asset.referenceUrl || '';
            const generating = asset.genStatus === 1;
            const checked = selected.has(asset.id);
            return (
              <div key={asset.id} className={`p-2 rounded-xl border bg-white transition flex flex-col ${checked ? 'border-indigo-500 ring-1 ring-indigo-200' : 'border-gray-200 hover:border-indigo-400 hover:shadow-sm'}`}>
                <div className="relative rounded-lg overflow-hidden bg-gray-50 aspect-[3/4] flex items-center justify-center">
                  <div className="absolute top-1.5 left-1.5 z-10">
                    <Checkbox checked={checked} onChange={(e) => toggle(asset.id, e.target.checked)} />
                  </div>
                  {imageUrl ? (
                    <Image
                      src={imageUrl}
                      alt={asset.name}
                      className="w-full h-full object-cover"
                      wrapperClassName="w-full h-full"
                    />
                  ) : (
                    <div className="flex flex-col items-center gap-1 text-gray-300">
                      <FileImageOutlined className="text-3xl" />
                      <span className="text-xs">{generating ? '图片生成中…' : '暂无图片'}</span>
                    </div>
                  )}
                  {generating && (
                    <div className="absolute inset-0 bg-white/60 flex items-center justify-center">
                      <Tag color="processing" bordered={false}>生成中</Tag>
                    </div>
                  )}
                </div>
                <div className="flex items-center gap-1 mt-2">
                  <Typography.Text strong ellipsis className="flex-1" title={asset.name}>
                    {asset.name}
                  </Typography.Text>
                  {isCharacter && !generating && !asset.sheetImageUrl && (
                    <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={() => genSheet(asset)}>
                      生成设定表
                    </Button>
                  )}
                  {isCharacter && asset.sheetImageUrl && !generating && (
                    <Tooltip title="重新生成设定表">
                      <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={() => genSheet(asset)}>
                        重生成
                      </Button>
                    </Tooltip>
                  )}
                  {!isCharacter && (
                    <Tooltip title={asset.referenceUrl ? '重新生成参考图' : '生成参考图'}>
                      <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={() => genSheet(asset)}>
                        {asset.referenceUrl ? '重生成' : '生成参考图'}
                      </Button>
                    </Tooltip>
                  )}
                </div>
                {asset.description && (
                  <Typography.Paragraph type="secondary" className="mt-1 mb-2 text-xs" ellipsis={{ rows: 2, tooltip: asset.description }}>
                    {asset.description}
                  </Typography.Paragraph>
                )}
                <div className="mt-auto text-right">
                  <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={() => onEdit(asset)}>
                    编辑
                  </Button>
                </div>
              </div>
            );
          })}
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

function SeriesStatusTag({ status }: { status: number | null }) {
  const meta = SERIES_STATUS[status ?? 2] ?? { label: '已完结', color: 'green' };
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
        <Form.Item noStyle shouldUpdate={(prev, cur) => prev.referenceUrl !== cur.referenceUrl}>
          {({ getFieldValue }) => {
            const refUrl = (getFieldValue('referenceUrl') ?? '').trim();
            return (
              <Form.Item name="referenceUrl" label="参考图 URL(可先经 系统配置→文件上传 获取)">
                <Space.Compact className="w-full">
                  <Input placeholder="https://…" />
                  {refUrl && (
                    <Image
                      src={refUrl}
                      alt="参考图"
                      width={64}
                      height={64}
                      className="rounded object-cover"
                      style={{ alignSelf: 'center', marginLeft: 8 }}
                    />
                  )}
                </Space.Compact>
              </Form.Item>
            );
          }}
        </Form.Item>
      </Form>
    </Modal>
  );
}

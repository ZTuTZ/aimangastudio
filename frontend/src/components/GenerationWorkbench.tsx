import { Alert, Button, Card, Checkbox, Collapse, Image, Modal, Radio, Select, Space, Switch, Tag, Typography } from 'antd';
import { CheckCircleOutlined, PauseCircleOutlined, PlayCircleOutlined, ThunderboltOutlined, WarningOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ASSET_TYPE_NAMES, projectsApi, type ChapterVO } from '@/api/projects';
import { tasksApi } from '@/api/tasks';

const STAGE_META: Record<string, { label: string }> = {
  SPLIT: { label: '拆话' },
  ASSET: { label: '资产提取' },
  SCRIPT: { label: '脚本' },
  SHEET: { label: '角色设定表' },
  REFERENCE: { label: '素材参考图' },
  LAYOUT: { label: '布局图' },
  IMAGE: { label: '成品页' },
};

const STAGE_STATUS: Record<number, { label: string; color: string }> = {
  0: { label: '排队', color: 'default' },
  1: { label: '进行中', color: 'processing' },
  2: { label: '完成', color: 'success' },
  3: { label: '失败', color: 'error' },
  4: { label: '已暂停', color: 'warning' },
  5: { label: '已停止', color: 'default' },
};

/**
 * 生成成品工作台(Phase 6.4):
 * 生成控制区(T6.4.1) + 素材预检卡片(T6.4.2) + 实时 Stage 进度(T6.4.3) + 页画廊(T6.4.4) + 暂停/继续(T6.4.5)。
 * BATCH 断点续跑由后端保证:已成功页面绝不重画。
 */
export function GenerationWorkbench({ projectId, project, chapters, onGoAssets, initialChapterId }: {
  projectId: number;
  project: { colorMode: string | null };
  chapters: ChapterVO[];
  onGoAssets: () => void;
  initialChapterId?: number;
}) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [scope, setScope] = useState<'PROJECT' | 'CHAPTER' | 'CHAPTERS'>('PROJECT');
  const [chapterId, setChapterId] = useState<number | undefined>(undefined);
  const [selectedChapterIds, setSelectedChapterIds] = useState<number[]>([]);
  const [chapterModalOpen, setChapterModalOpen] = useState(false);
  const [colorMode, setColorMode] = useState<string>(project.colorMode ?? 'partial');
  const [skipGenerated, setSkipGenerated] = useState(true);
  const [forceLayout, setForceLayout] = useState(false);
  const [forceImage, setForceImage] = useState(false);

  const preflightOpts = scope === 'CHAPTER'
    ? { chapterId }
    : scope === 'CHAPTERS' && selectedChapterIds.length > 0
      ? { chapterIds: selectedChapterIds }
      : undefined;
  const { data: preflight, isLoading: preflightLoading } = useQuery({
    queryKey: ['preflight', projectId, preflightOpts?.chapterId ?? 'all', preflightOpts?.chapterIds?.join(',') ?? ''],
    queryFn: () => projectsApi.generationPreflight(projectId, preflightOpts),
  });

  // 活跃 BATCH 任务(0排队/1进行中/6停止中)
  const { data: batchPage } = useQuery({
    queryKey: ['tasks', projectId, 'batch'],
    queryFn: () => tasksApi.list({ page: 1, size: 1, projectId, type: 'BATCH' }),
    refetchInterval: 5000,
    refetchIntervalInBackground: true,
  });
  const batchTask = batchPage?.records?.[0];
  const batchActive = !!batchTask && [0, 1, 6].includes(batchTask.status);

  // Stage 实时进度(BATCH 活跃时 3s 轮询;SSE 事件也会触发失效)
  const { data: stages } = useQuery({
    queryKey: ['pipeline', projectId],
    queryFn: () => projectsApi.pipelineStages(projectId),
    refetchInterval: batchActive ? 3000 : false,
    refetchIntervalInBackground: true,
  });
  const layoutStage = stages?.find((s) => s.stageType === 'LAYOUT');
  const imageStage = stages?.find((s) => s.stageType === 'IMAGE');

  const start = useMutation({
    mutationFn: () => projectsApi.generateBatch(projectId, {
      scope,
      chapterId: scope === 'CHAPTER' ? chapterId : undefined,
      chapterIds: scope === 'CHAPTERS' ? selectedChapterIds : undefined,
      colorMode,
      skipGenerated,
      forceLayout,
      forceImage,
    }),
    onSuccess: (task) => {
      message.success(`生成任务 #${task.id} 已创建,布局图与成品页将并发生成`);
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      queryClient.invalidateQueries({ queryKey: ['pipeline', projectId] });
      queryClient.invalidateQueries({ queryKey: ['preflight', projectId] });
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '创建失败'),
  });

  const pauseOrResume = useMutation({
    mutationFn: (action: 'pause' | 'resume') =>
      action === 'pause' ? projectsApi.pausePipeline(projectId) : projectsApi.resumePipeline(projectId),
    onSuccess: (_d, action) => {
      message.success(action === 'pause' ? '已请求暂停:在跑的请求完成后保存,不再领取新页' : '已继续:从未完成页面接着跑');
      queryClient.invalidateQueries({ queryKey: ['pipeline', projectId] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '操作失败'),
  });

  const canStart = !!preflight?.ready && !batchActive && !!preflight?.pageCount
    && (scope !== 'CHAPTERS' || selectedChapterIds.length > 0);
  const required = preflight?.stats?.find((s) => s.assetType === 1);

  return (
    <div className="flex flex-col gap-4 max-w-4xl">
      {/* T6.4.1 生成控制区 */}
      <Card size="small" title="生成范围与参数">
        <div className="flex flex-col gap-3">
          <Space wrap>
            <Radio.Group
              value={scope}
              onChange={(e) => {
                setScope(e.target.value);
                setChapterId(undefined);
              }}
              optionType="button"
              options={[
                { value: 'PROJECT', label: '整部作品' },
                { value: 'CHAPTER', label: '单话' },
                { value: 'CHAPTERS', label: '多话' },
              ]}
            />
            {scope === 'CHAPTER' && (
              <Select
                style={{ width: 280 }}
                placeholder="选择话"
                value={chapterId}
                onChange={setChapterId}
                options={chapters.map((c) => ({ value: c.id, label: `第${c.chapterNo}话 ${c.title}` }))}
              />
            )}
            {scope === 'CHAPTERS' && (
              <Space wrap>
                <Button onClick={() => setChapterModalOpen(true)}>选择话({selectedChapterIds.length})</Button>
                {selectedChapterIds.length > 0 && (
                  <Typography.Text type="secondary" className="text-xs">
                    已选 {selectedChapterIds.length} 话 · 约 {chapters
                      .filter((c) => selectedChapterIds.includes(c.id))
                      .reduce((sum, c) => sum + (c.pageCount ?? 0), 0)} 页
                  </Typography.Text>
                )}
              </Space>
            )}
          </Space>
          <Space wrap>
            <Typography.Text type="secondary" className="text-sm">色彩模式</Typography.Text>
            <Select
              style={{ width: 160 }}
              value={colorMode}
              onChange={setColorMode}
              options={[
                { value: 'partial', label: '局部上色' },
                { value: 'monochrome', label: '黑白' },
                { value: 'color', label: '全彩' },
              ]}
            />
          </Space>
          <Space wrap size="large">
            <Checkbox checked={skipGenerated} onChange={(e) => setSkipGenerated(e.target.checked)}>
              跳过已生成页面(默认开)
            </Checkbox>
            <Switch checkedChildren="重布局" unCheckedChildren="重布局" checked={forceLayout} onChange={setForceLayout} />
            <Typography.Text type="secondary" className="text-xs">重新生成全部布局图</Typography.Text>
            <Switch checkedChildren="重成品" unCheckedChildren="重成品" checked={forceImage} onChange={setForceImage} />
            <Typography.Text type="secondary" className="text-xs">重新生成全部成品页</Typography.Text>
          </Space>
        </div>
      </Card>

      {/* T6.4.2 素材预检卡片 */}
      {preflight && (
        <Card size="small" title="素材预检" loading={preflightLoading}>
          <div className="flex flex-col gap-3">
            <Alert
              type={preflight.ready ? 'success' : 'warning'}
              showIcon
              icon={preflight.ready ? <CheckCircleOutlined /> : <WarningOutlined />}
              message={preflight.ready
                ? `素材就绪(共 ${preflight.pageCount} 页),可以开始生成`
                : '素材未就绪:缺必需角色参考图,不能开始'}
            />
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              {(preflight.stats.length === 0 ? [] : preflight.stats).map((s) => (
                <div key={s.assetType} className="p-2 rounded-lg border border-gray-200 bg-white text-center">
                  <Typography.Text type="secondary" className="text-xs">{ASSET_TYPE_NAMES[s.assetType] ?? '素材'}</Typography.Text>
                  <div className="text-lg font-semibold">{s.ready} <span className="text-xs text-gray-400">/ {s.total}</span></div>
                </div>
              ))}
              {required === undefined && preflight.pageCount > 0 && (
                <div className="col-span-2 sm:col-span-4">
                  <Typography.Text type="secondary" className="text-xs">
                    还没有页-素材绑定,请先「重建绑定」。
                  </Typography.Text>
                </div>
              )}
            </div>
            {preflight.missingRequiredAssets.length > 0 && (
              <div>
                <Typography.Text type="danger" className="text-xs">
                  缺少角色参考图:{preflight.missingRequiredAssets.map((a) => a.name).join('、')}
                </Typography.Text>
                <Button size="small" type="primary" className="ml-3" onClick={onGoAssets}>前往资产库</Button>
              </div>
            )}
            {preflight.optionalMissingAssets.length > 0 && (
              <Typography.Text type="secondary" className="text-xs">
                可选素材缺参考图 {preflight.optionalMissingAssets.length} 个:将以文字设定生成,不阻塞。
              </Typography.Text>
            )}
          </div>
        </Card>
      )}

      {/* 开始 / 暂停 / 继续 */}
      <Space>
        <Button
          type="primary"
          size="large"
          icon={<ThunderboltOutlined />}
          disabled={!canStart}
          loading={start.isPending}
          onClick={() => start.mutate()}
        >
          {batchActive ? '生成任务进行中…' : '开始生成成品页'}
        </Button>
        {batchActive && (
          <Button icon={<PauseCircleOutlined />} onClick={() => pauseOrResume.mutate('pause')}>暂停</Button>
        )}
        <Button
          icon={<PlayCircleOutlined />}
          disabled={batchActive}
          onClick={() => pauseOrResume.mutate('resume')}
        >
          继续流水线
        </Button>
        {batchTask && (
          <Typography.Text type="secondary" className="text-xs">
            最近 BATCH 任务 #{batchTask.id}:进度 {batchTask.progress}%,成功 {batchTask.successCount},失败 {batchTask.failCount}
            {batchTask.error ? `,${batchTask.error}` : ''}
          </Typography.Text>
        )}
      </Space>

      {/* T6.4.3 实时 Stage 进度 */}
      {(layoutStage || imageStage) && (
        <Card size="small" title="生成进度">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {[layoutStage, imageStage].filter(Boolean).map((stage) => {
              const meta = STAGE_STATUS[stage!.status ?? 0] ?? STAGE_STATUS[0];
              return (
                <div key={stage!.id} className="p-3 rounded-lg border border-gray-200 bg-white">
                  <div className="flex items-center justify-between">
                    <Typography.Text strong>{STAGE_META[stage!.stageType]?.label ?? stage!.stageType}</Typography.Text>
                    <Tag color={meta.color} bordered={false}>{meta.label}</Tag>
                  </div>
                  <div className="text-2xl font-semibold mt-1">
                    {stage!.successCount ?? 0} <span className="text-sm text-gray-400">/ {stage!.totalCount ?? 0}</span>
                    {(stage!.failedCount ?? 0) > 0 && (
                      <span className="text-sm text-red-500 ml-2">失败 {stage!.failedCount}</span>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </Card>
      )}

      {/* T6.4.4 页画廊(按话折叠;重试按钮在 Phase 6.5 单页接口上线后接入) */}
      <PageGallery projectId={projectId} chapters={chapters} initialChapterId={initialChapterId} />

      {/* 多话批量选择对话框 */}
      <Modal
        open={chapterModalOpen}
        title="批量选择要生成的话"
        okText={`生成已选 ${selectedChapterIds.length} 话`}
        cancelText="取消"
        confirmLoading={start.isPending}
        okButtonProps={{ disabled: selectedChapterIds.length === 0 }}
        onCancel={() => setChapterModalOpen(false)}
        onOk={() => {
          setChapterModalOpen(false);
          start.mutate();
        }}
      >
        <div className="flex flex-col gap-3">
          <Space>
            <Button size="small" onClick={() => setSelectedChapterIds(chapters.map((c) => c.id))}>全选</Button>
            <Button size="small" onClick={() => setSelectedChapterIds([])}>清空</Button>
            <Typography.Text type="secondary" className="text-xs">
              共 {chapters.length} 话 · 已选 {selectedChapterIds.length} 话 · 约 {chapters
                .filter((c) => selectedChapterIds.includes(c.id))
                .reduce((sum, c) => sum + (c.pageCount ?? 0), 0)} 页
            </Typography.Text>
          </Space>
          <div className="max-h-80 overflow-auto flex flex-col gap-1">
            {chapters.map((c) => (
              <label key={c.id} className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-gray-50 cursor-pointer">
                <Checkbox
                  checked={selectedChapterIds.includes(c.id)}
                  onChange={(e) => setSelectedChapterIds((prev) =>
                    e.target.checked ? [...prev, c.id] : prev.filter((x) => x !== c.id))}
                />
                <span className="text-sm">第{c.chapterNo}话 {c.title}</span>
                <Typography.Text type="secondary" className="text-xs ml-auto">
                  {c.pageCount ?? 0} 页{(c.status ?? 0) < 2 ? ' · 脚本未完成' : ''}
                </Typography.Text>
              </label>
            ))}
          </div>
          <Typography.Text type="secondary" className="text-xs">
            所选话将合并为一个生成任务;素材预检以所选话并集计算,缺必需角色素材时无法开始。
          </Typography.Text>
        </div>
      </Modal>
    </div>
  );
}

/** 页画廊:按话 → 页,展示 未生成/生成中/成功/失败 与 OSS 图 */
function PageGallery({ projectId, chapters, initialChapterId }: {
  projectId: number;
  chapters: ChapterVO[];
  initialChapterId?: number;
}) {
  const [openKeys, setOpenKeys] = useState<string[]>(
    initialChapterId ? [String(initialChapterId)] : []);
  return (
    <Card size="small" title="页画廊">
      {chapters.length === 0 ? (
        <Typography.Text type="secondary" className="text-sm">暂无话</Typography.Text>
      ) : (
        <Collapse
          activeKey={openKeys}
          onChange={(keys) => setOpenKeys(Array.isArray(keys) ? keys as string[] : [keys as string])}
          items={chapters.map((c) => ({
            key: String(c.id),
            label: `第${c.chapterNo}话 ${c.title}(${c.pageCount ?? 0} 页)`,
            children: <ChapterPages projectId={projectId} chapterId={c.id} />,
          }))}
        />
      )}
    </Card>
  );
}

function ChapterPages({ projectId, chapterId }: { projectId: number; chapterId: number }) {
  const navigate = useNavigate();
  const { data: pages, isLoading } = useQuery({
    queryKey: ['pages', chapterId],
    queryFn: () => projectsApi.pages(chapterId),
    refetchInterval: 5000,
    refetchIntervalInBackground: true,
  });
  if (isLoading) return <Typography.Text type="secondary">加载中…</Typography.Text>;
  if (!pages || pages.length === 0) return <Typography.Text type="secondary">本话暂无页面</Typography.Text>;
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
      {pages.map((p) => {
        const status = p.generateStatus ?? 0;
        const color = status === 2 ? 'success' : status === 1 ? 'processing' : status === 3 ? 'error' : 'default';
        const label = status === 2 ? '成功' : status === 1 ? '生成中' : status === 3 ? '失败' : '未生成';
        const imageStale = (p.imageScriptVersion ?? 0) < (p.scriptVersion ?? 1);
        // 成品未出时显示布局图缩略(可看清构图进度)
        const previewUrl = p.generatedImageUrl || p.layoutImageUrl || '';
        const previewIsLayout = !p.generatedImageUrl && !!p.layoutImageUrl;
        return (
          <div
            key={p.id}
            className="p-2 rounded-lg border border-gray-200 bg-white cursor-pointer hover:border-indigo-400 hover:shadow-sm transition"
            onClick={() => navigate(`/projects/${projectId}/pages/${p.id}`, {
              state: { from: 'generate', chapterId }
            })}
            title="点击进入页详情"
          >
            <div className="aspect-[3/4] rounded bg-gray-50 flex items-center justify-center overflow-hidden relative">
              {previewUrl ? (
                <>
                  <Image src={previewUrl} alt={`第${p.pageNo}页`} className="w-full h-full object-cover" wrapperClassName="w-full h-full" />
                  {previewIsLayout && (
                    <Tag color="blue" bordered={false} className="absolute top-1 left-1 z-10">布局图</Tag>
                  )}
                  {imageStale && status === 2 && (
                    <Tag color="orange" bordered={false} className="absolute top-1 right-1 z-10">脚本已改</Tag>
                  )}
                </>
              ) : (
                <Typography.Text type="secondary" className="text-xs">{label}</Typography.Text>
              )}
              {status === 1 && (
                <div className="absolute inset-0 bg-white/60 flex items-center justify-center">
                  <Tag color="processing" bordered={false}>生成中</Tag>
                </div>
              )}
            </div>
            <div className="flex items-center justify-between mt-1">
              <Typography.Text type="secondary" className="text-xs">第 {p.pageNo} 页</Typography.Text>
              <Tag color={color} bordered={false}>{label}</Tag>
            </div>
            {status === 3 && p.failReason && (
              <Typography.Text type="danger" className="text-xs block" ellipsis={{ tooltip: p.failReason }}>
                {p.failReason}
              </Typography.Text>
            )}
          </div>
        );
      })}
    </div>
  );
}

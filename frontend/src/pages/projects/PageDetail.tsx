import { Alert, Button, Card, Col, Image, Input, Modal, Row, Select, Space, Tag, Typography } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined, SaveOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { projectsApi } from '@/api/projects';

/**
 * 页详情(Phase 6.5 T6.5.1):
 * 左:脚本信息(旁白/对白/visual/场景,可编辑,保存即脚本版本+1) 中:布局图 右:成品图。
 * 过期标记(T6.5.4):imageScriptVersion < scriptVersion → 成品已过期(旧图保留在生成记录中)。
 */
export function PageDetail() {
  const { id, pageId } = useParams();
  const navigate = useNavigate();
  const projectId = Number(id);
  const pid = Number(pageId);
  const { message } = App.useApp();
  const queryClient = useQueryClient();

  const { data: page } = useQuery({
    queryKey: ['page', pid],
    queryFn: () => projectsApi.page(pid),
    refetchInterval: 8000,
  });

  const [narration, setNarration] = useState('');
  const [visual, setVisual] = useState('');
  const [sceneDescription, setSceneDescription] = useState('');
  const [regenColorMode, setRegenColorMode] = useState<string | undefined>(undefined);
  const [repaintOpen, setRepaintOpen] = useState(false);
  const [repaintPrompt, setRepaintPrompt] = useState('');
  const [maskUrl, setMaskUrl] = useState('');

  useEffect(() => {
    if (page) {
      setNarration(page.narration ?? '');
      setVisual(page.visual ?? '');
      setSceneDescription(page.sceneDescription ?? '');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page?.id, page?.scriptVersion]);

  const save = useMutation({
    mutationFn: () => projectsApi.updatePage(pid, {
      narration,
      visual,
      sceneDescription,
    }),
    onSuccess: () => {
      message.success('脚本已保存(脚本版本 +1,布局/成品图将标记为待更新)');
      queryClient.invalidateQueries({ queryKey: ['page', pid] });
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '保存失败'),
  });

  const regenLayout = useMutation({
    mutationFn: () => projectsApi.generatePageLayout(pid),
    onSuccess: (t) => {
      message.success(`布局重生成任务 #${t.id} 已创建`);
      queryClient.invalidateQueries({ queryKey: ['page', pid] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '操作失败'),
  });

  const colorize = useMutation({
    mutationFn: () => projectsApi.colorizePage(pid),
    onSuccess: (t) => {
      message.success(`上色任务 #${t.id} 已创建`);
      queryClient.invalidateQueries({ queryKey: ['page', pid] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '操作失败'),
  });

  const clean = useMutation({
    mutationFn: () => projectsApi.cleanPage(pid),
    onSuccess: (t) => {
      message.success(`清晰化任务 #${t.id} 已创建`);
      queryClient.invalidateQueries({ queryKey: ['page', pid] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '操作失败'),
  });

  const repaint = useMutation({
    mutationFn: () => projectsApi.repaintPage(pid, { repaintPrompt, maskUrl }),
    onError: (e) => message.error(e instanceof Error ? e.message : '操作失败'),
  });

  const regenImage = useMutation({
    mutationFn: () => projectsApi.generatePageImage(pid, regenColorMode),
    onSuccess: (t) => {
      message.success(`成品重生成任务 #${t.id} 已创建`);
      queryClient.invalidateQueries({ queryKey: ['page', pid] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '操作失败'),
  });

  if (!page) {
    return <Card loading />;
  }
  const scriptVersion = page.scriptVersion ?? 1;
  const layoutStale = (page.layoutScriptVersion ?? 0) < scriptVersion;
  const imageStale = (page.imageScriptVersion ?? 0) < scriptVersion;
  const dialogueLines: string[] = (() => {
    try {
      const arr = JSON.parse(page.dialogue ?? '[]');
      return Array.isArray(arr) ? arr.map((d: { speaker: string; line: string }) => `${d.speaker}:${d.line}`) : [];
    } catch {
      return [];
    }
  })();

  return (
    <div className="flex flex-col gap-4">
      <Space>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(`/projects/${projectId}`)}>返回作品</Button>
        <Typography.Title level={4} style={{ margin: 0 }}>第 {page.pageNo} 页</Typography.Title>
        {layoutStale && <Tag color="orange" bordered={false}>布局已过期(脚本 v{scriptVersion})</Tag>}
        {imageStale && <Tag color="red" bordered={false}>成品已过期(脚本 v{scriptVersion})</Tag>}
      </Space>

      <Row gutter={16}>
        {/* 左:脚本信息 */}
        <Col xs={24} lg={8}>
          <Card size="small" title="脚本" extra={
            <Button size="small" icon={<SaveOutlined />} loading={save.isPending} onClick={() => save.mutate()}>
              保存
            </Button>
          }>
            <div className="flex flex-col gap-3">
              <div>
                <Typography.Text type="secondary" className="text-xs">旁白</Typography.Text>
                <Input.TextArea rows={3} value={narration} onChange={(e) => setNarration(e.target.value)} />
              </div>
              <div>
                <Typography.Text type="secondary" className="text-xs">对白(只读)</Typography.Text>
                <div className="rounded border border-gray-200 p-2 text-xs min-h-16 bg-gray-50">
                  {dialogueLines.length === 0
                    ? <Typography.Text type="secondary">(无对白)</Typography.Text>
                    : dialogueLines.map((l, i) => <div key={i}>{l}</div>)}
                </div>
              </div>
              <div>
                <Typography.Text type="secondary" className="text-xs">画面描述(visual)</Typography.Text>
                <Input.TextArea rows={4} value={visual} onChange={(e) => setVisual(e.target.value)} />
              </div>
              <div>
                <Typography.Text type="secondary" className="text-xs">场景描述</Typography.Text>
                <Input.TextArea rows={2} value={sceneDescription} onChange={(e) => setSceneDescription(e.target.value)} />
              </div>
              <Typography.Text type="secondary" className="text-xs">
                修改并保存后脚本版本 +1,布局/成品图将被标记为过期,需要重新生成(旧图保留在生成记录)。
              </Typography.Text>
            </div>
          </Card>
        </Col>

        {/* 中:布局图 */}
        <Col xs={24} md={12} lg={8}>
          <Card size="small" title="布局图(构图约束)" extra={
            <Button size="small" icon={<ReloadOutlined />} loading={regenLayout.isPending} onClick={() => regenLayout.mutate()}>
              重生成布局
            </Button>
          }>
            <div className="aspect-[3/4] rounded bg-gray-50 flex items-center justify-center overflow-hidden">
              {page.layoutImageUrl
                ? <Image src={page.layoutImageUrl} alt="布局图" className="w-full object-contain" />
                : <Typography.Text type="secondary" className="text-xs">尚未生成布局图</Typography.Text>}
            </div>
          </Card>
        </Col>

        {/* 右:成品图 */}
        <Col xs={24} md={12} lg={8}>
          <Card size="small" title="成品图" extra={
            <Space>
              <Select
                size="small"
                style={{ width: 110 }}
                placeholder="色彩(默认作品)"
                value={regenColorMode}
                onChange={setRegenColorMode}
                allowClear
                options={[
                  { value: 'partial', label: '局部上色' },
                  { value: 'monochrome', label: '黑白' },
                  { value: 'color', label: '全彩' },
                ]}
              />
              <Button size="small" type="primary" icon={<ThunderboltOutlined />} loading={regenImage.isPending} onClick={() => regenImage.mutate()}>
                重生成成品
              </Button>
            </Space>
          }>
            <div className="aspect-[3/4] rounded bg-gray-50 flex items-center justify-center overflow-hidden relative">
              {page.generatedImageUrl
                ? <Image src={page.generatedImageUrl} alt="成品图" className="w-full object-contain" />
                : <Typography.Text type="secondary" className="text-xs">尚未生成成品图</Typography.Text>}
            </div>
            {page.failReason && page.generateStatus === 3 && (
              <Alert type="error" className="mt-2" message={page.failReason} />
            )}
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <Button size="small" onClick={() => colorize.mutate()} loading={colorize.isPending}>上色</Button>
              <Button size="small" onClick={() => clean.mutate()} loading={clean.isPending}>清晰化</Button>
              <Button size="small" onClick={() => setRepaintOpen(true)}>局部重绘</Button>
            </div>
          </Card>
        </Col>
      </Row>

      <Modal
        open={repaintOpen}
        title="局部重绘"
        okText="开始重绘"
        cancelText="取消"
        confirmLoading={repaint.isPending}
        onCancel={() => setRepaintOpen(false)}
        onOk={async () => {
          if (!repaintPrompt.trim()) {
            message.warning('请填写重绘提示词');
            return;
          }
          if (!maskUrl) {
            message.warning('请上传遮罩图(白色=重绘区域)');
            return;
          }
          try {
            const t = await projectsApi.repaintPage(pid, { repaintPrompt, maskUrl });
            message.success(`局部重绘任务 #${t.id} 已创建`);
            setRepaintOpen(false);
            setMaskUrl('');
            queryClient.invalidateQueries({ queryKey: ['page', pid] });
          } catch (e) {
            message.error(e instanceof Error ? e.message : '操作失败');
          }
        }}
      >
        <div className="flex flex-col gap-3">
          <div>
            <Typography.Text type="secondary" className="text-xs">重绘提示词(要修改成什么样)</Typography.Text>
            <Input.TextArea rows={3} value={repaintPrompt} onChange={(e) => setRepaintPrompt(e.target.value)} />
          </div>
          <div>
            <Typography.Text type="secondary" className="text-xs">遮罩图(白色=重绘区域,黑色=保留)</Typography.Text>
            <input
              type="file"
              accept="image/*"
              className="block mt-1 text-xs"
              onChange={async (e) => {
                const file = e.target.files?.[0];
                if (!file) return;
                try {
                  const r = await projectsApi.uploadFile(file);
                  setMaskUrl(r.url);
                  message.success('遮罩图已上传');
                } catch (err) {
                  message.error(err instanceof Error ? err.message : '上传失败');
                }
              }}
            />
            {maskUrl && <Typography.Text className="text-xs">已上传 ✓</Typography.Text>}
          </div>
        </div>
      </Modal>
    </div>
  );
}

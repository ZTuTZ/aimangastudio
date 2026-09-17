import { Alert, Button, Input, Popconfirm, Radio, Segmented, Select, Slider, Space, Tag, Typography } from 'antd';
import { ClearOutlined, PlusOutlined, SaveOutlined, SyncOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { projectsApi, type PageVO, type TextLayerDto, type TextLayerElement } from '@/api/projects';

type EditableElement = TextLayerElement;

const DEVICES = [
  { value: 375, label: '375' },
  { value: 390, label: '390' },
  { value: 430, label: '430' },
  { value: 768, label: '768' },
];

const BUBBLE_PRESETS = [
  { value: 'DEFAULT_DIALOGUE', label: '对白气泡' },
  { value: 'NARRATION_BOX', label: '旁白框' },
  { value: 'THOUGHT_SIMPLE', label: '心理框' },
];

const ELEMENT_TYPES = [
  { value: 'DIALOGUE', label: '对白' },
  { value: 'NARRATION', label: '旁白' },
  { value: 'THOUGHT', label: '心理' },
  { value: 'SFX', label: 'SFX' },
];

const clamp01 = (v: number) => Math.max(0, Math.min(1, v));
const newUid = () => 'TXT_' + Math.random().toString(16).slice(2, 14).toUpperCase();

/**
 * 对白层编辑器(Phase 7.8):
 * 漫画底图 + 动态 Text Layer 覆盖(归一化坐标),拖动/缩放/编辑文字/调 tail/多尺寸预览/告警。
 * 保存的是归一化协议数据,绝不合成进成品图。
 */
export function TextLayerEditor({ projectId, page }: { projectId: number; page: PageVO }) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const wrapRef = useRef<HTMLDivElement>(null);
  const [elements, setElements] = useState<EditableElement[]>([]);
  const [selectedUid, setSelectedUid] = useState<string | null>(null);
  const [sourceVersion, setSourceVersion] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [device, setDevice] = useState(390);
  const [box, setBox] = useState({ w: 0, h: 0 });
  const dragRef = useRef<{
    mode: 'move' | 'resize' | 'tail';
    uid: string;
    startX: number;
    startY: number;
    orig: EditableElement;
    rect: DOMRect;
  } | null>(null);

  const { data: layer } = useQuery({
    queryKey: ['text-layer', page.id],
    queryFn: () => projectsApi.textLayer(page.id),
  });
  // 角色名单(speaker 告警用)
  const { data: assets } = useQuery({
    queryKey: ['assets', projectId],
    queryFn: () => projectsApi.assets(projectId),
  });

  const apply = (dto: TextLayerDto) => {
    setElements(dto.elements.map((e) => ({ ...e })));
    setSourceVersion(dto.textLayoutSourceVersion ?? 0);
    setDirty(false);
  };

  const initialized = useRef(false);
  useEffect(() => {
    if (!layer || initialized.current) return;
    initialized.current = true;
    const hasContent = (page.dialogue && page.dialogue !== '[]') || !!page.narration;
    if (layer.elements.length === 0 && hasContent) {
      projectsApi.initializeTextLayer(page.id).then(apply);
    } else {
      apply(layer);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [layer]);

  // 容器尺寸测量(字号 = min(w,h) × ratio)
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const observer = new ResizeObserver(() => {
      setBox({ w: el.clientWidth, h: el.clientHeight });
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [device]);

  const startDrag = (e: React.PointerEvent, el: EditableElement, mode: 'move' | 'resize' | 'tail') => {
    e.stopPropagation();
    e.preventDefault();
    const rect = wrapRef.current?.getBoundingClientRect();
    if (!rect) return;
    dragRef.current = { mode, uid: el.uid, startX: e.clientX, startY: e.clientY, orig: { ...el }, rect };
    setSelectedUid(el.uid);
    // 触发上面的 effect:利用 elements.length 无关,这里手动挂一次监听
    const onMove = (ev: PointerEvent) => {
      const r = dragRef.current?.rect ?? rect;
      const dx = (ev.clientX - dragRef.current!.startX) / r.width;
      const dy = (ev.clientY - dragRef.current!.startY) / r.height;
      const d = dragRef.current!;
      setElements((prev) => prev.map((item) => {
        if (item.uid !== d.uid) return item;
        if (d.mode === 'move') {
          return { ...item, position: { ...item.position, x: clamp01(d.orig.position.x + dx), y: clamp01(d.orig.position.y + dy) } };
        }
        if (d.mode === 'resize') {
          const w = clamp01(d.orig.position.width + dx);
          const h = Math.max(0.02, clamp01((d.orig.position.height ?? 0.1) + dy));
          return { ...item, position: { ...item.position, width: Math.min(w, 1 - item.position.x), height: Math.min(h, 1 - item.position.y) } };
        }
        return {
          ...item,
          bubble: { ...item.bubble, tail: { x: clamp01((d.orig.bubble.tail?.x ?? 0.5) + dx), y: clamp01((d.orig.bubble.tail?.y ?? 0.5) + dy) } },
        };
      }));
      setDirty(true);
    };
    const onUp = () => {
      dragRef.current = null;
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };

  const save = useMutation({
    mutationFn: () => {
      const payload: TextLayerDto = {
        schemaVersion: 'comic-text-layer-1.0',
        pageId: page.id,
        pageVersion: page.scriptVersion ?? null,
        elements: elements.map((e, i) => ({ ...e, sortOrder: i + 1 })),
      };
      return projectsApi.saveTextLayer(page.id, payload);
    },
    onSuccess: (dto) => {
      apply(dto);
      message.success('对白层已保存');
      queryClient.invalidateQueries({ queryKey: ['text-layer', page.id] });
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '保存失败'),
  });

  const doSync = useMutation({
    mutationFn: () => projectsApi.syncTextLayer(page.id),
    onSuccess: (dto) => {
      apply(dto);
      message.success('已同步脚本文本(保留布局位置)');
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '同步失败'),
  });

  const doReset = useMutation({
    mutationFn: () => projectsApi.resetTextLayer(page.id),
    onSuccess: (dto) => {
      apply(dto);
      message.success('已恢复默认布局');
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '重置失败'),
  });

  const selected = elements.find((e) => e.uid === selectedUid) ?? null;

  const updateSelected = (patch: Partial<EditableElement>) => {
    if (!selectedUid) return;
    setElements((prev) => prev.map((e) => (e.uid === selectedUid ? { ...e, ...patch } : e)));
    setDirty(true);
  };

  const addElement = () => {
    const el: EditableElement = {
      uid: newUid(),
      type: 'DIALOGUE',
      dialogueIndex: null,
      speaker: '',
      text: '新对白',
      position: { x: 0.35, y: 0.4, width: 0.3, height: 0.1 },
      style: { fontPreset: 'DEFAULT_DIALOGUE', fontSizeRatio: 0.028, align: 'CENTER', maxLines: 4 },
      bubble: { preset: 'DEFAULT_DIALOGUE', tail: { x: 0.5, y: 0.5 } },
      sortOrder: elements.length + 1,
    };
    setElements((prev) => [...prev, el]);
    setSelectedUid(el.uid);
    setDirty(true);
  };

  const deleteSelected = () => {
    if (!selectedUid) return;
    setElements((prev) => prev.filter((e) => e.uid !== selectedUid));
    setSelectedUid(null);
    setDirty(true);
  };

  // ---------- 告警(Phase 7.8 §24) ----------
  const warnings = useMemo(() => {
    const list: string[] = [];
    const speakers = new Set<string>();
    (assets ?? []).forEach((a) => {
      if (a.name) speakers.add(a.name);
      try {
        JSON.parse(a.aliases ?? '[]').forEach((s: string) => speakers.add(s));
      } catch { /* ignore */ }
    });
    for (const el of elements) {
      const label = `${el.type}「${(el.speaker || el.text.slice(0, 6)) || el.uid}」`;
      if (!el.text.trim()) list.push(`${label} 文本为空`);
      const { x, y, width, height } = el.position;
      if (x < 0.03 || y < 0.03 || x + width > 0.97 || y + (height ?? 0.1) > 0.97) {
        list.push(`${label} 超出安全区(3%~97%),部分客户端可能被裁切`);
      }
      const ratio = el.style.fontSizeRatio;
      const px = Math.min(box.w, box.h) * ratio;
      if (px <= 0) continue;
      if (px > 0) {
        const charsPerLine = Math.max(1, Math.floor((width * box.w - px * 0.6) / px));
        const lines = Math.ceil(el.text.length / charsPerLine);
        const maxLines = el.style.maxLines ?? 4;
        if (lines > maxLines) list.push(`${label} 文字约 ${lines} 行,超过 maxLines=${maxLines}`);
        if (width * box.w < px * 3) list.push(`${label} 宽度过小,无法容纳合理字号`);
      }
      if (el.type === 'DIALOGUE' && el.speaker && !speakers.has(el.speaker)) {
        list.push(`${label} speaker「${el.speaker}」与项目角色不一致`);
      }
    }
    // 重叠检测(面积交叠 > 较小者 30%)
    for (let i = 0; i < elements.length; i++) {
      for (let j = i + 1; j < elements.length; j++) {
        const a = elements[i].position;
        const b = elements[j].position;
        const ix = Math.max(0, Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x));
        const iy = Math.max(0, Math.min(a.y + (a.height ?? 0.1), b.y + (b.height ?? 0.1)) - Math.max(a.y, b.y));
        const inter = ix * iy;
        const minArea = Math.min(a.width * (a.height ?? 0.1), b.width * (b.height ?? 0.1));
        if (inter > minArea * 0.3) {
          list.push(`元素 ${elements[i].uid} 与 ${elements[j].uid} 严重重叠`);
        }
      }
    }
    return list;
  }, [elements, box, assets]);

  const stale = (page.scriptVersion ?? 1) > sourceVersion && elements.length > 0;

  return (
    <div className="flex flex-col gap-3">
      {stale && (
        <Alert
          type="warning"
          showIcon
          message="脚本文本已变更,当前对白布局可能需要同步"
          action={<Button size="small" onClick={() => doSync.mutate()} loading={doSync.isPending}>同步文本,保留布局</Button>}
        />
      )}
      {warnings.length > 0 && (
        <Alert type="warning" showIcon message={`${warnings.length} 条布局提示(不阻止保存)`}
          description={<div className="text-xs flex flex-col gap-0.5">{warnings.slice(0, 6).map((w, i) => <span key={i}>· {w}</span>)}</div>} />
      )}

      <div className="grid grid-cols-1 lg:grid-cols-[240px_1fr_260px] gap-3">
        {/* 元素列表 */}
        <div className="flex flex-col gap-1 max-h-[520px] overflow-auto">
          {elements.map((el) => (
            <div
              key={el.uid}
              className={`p-2 rounded-lg border cursor-pointer text-xs ${selectedUid === el.uid ? 'border-indigo-500 bg-indigo-50' : 'border-gray-200 bg-white hover:border-indigo-300'}`}
              onClick={() => setSelectedUid(el.uid)}
            >
              <div className="flex items-center gap-1">
                <Tag bordered={false} color={el.type === 'DIALOGUE' ? 'blue' : el.type === 'NARRATION' ? 'orange' : 'default'}>
                  {ELEMENT_TYPES.find((t) => t.value === el.type)?.label ?? el.type}
                </Tag>
                {el.speaker && <Typography.Text strong className="text-xs">{el.speaker}</Typography.Text>}
              </div>
              <Typography.Text type="secondary" className="text-xs" ellipsis>{el.text}</Typography.Text>
            </div>
          ))}
          <Button size="small" icon={<PlusOutlined />} onClick={addElement}>新增文本框</Button>
        </div>

        {/* 预览区 */}
        <div className="overflow-auto flex justify-center bg-gray-100 rounded-lg p-2">
          <div
            ref={wrapRef}
            className="relative select-none shadow-md"
            style={{ width: device, maxWidth: '100%' }}
            onPointerDown={() => setSelectedUid(null)}
          >
            {page.generatedImageUrl ? (
              <img src={page.generatedImageUrl} alt="成品页" className="block w-full" draggable={false} />
            ) : (
              <div className="aspect-[3/4] bg-gray-200 flex items-center justify-center text-xs text-gray-400">暂无成品图</div>
            )}
            {elements.map((el) => {
              const fontPx = Math.min(box.w || device, box.h || device * 1.33) * el.style.fontSizeRatio;
              const selectedEl = el.uid === selectedUid;
              return (
                <div
                  key={el.uid}
                  className="absolute"
                  style={{
                    left: `${el.position.x * 100}%`,
                    top: `${el.position.y * 100}%`,
                    width: `${el.position.width * 100}%`,
                    height: el.position.height ? `${el.position.height * 100}%` : 'auto',
                    fontSize: fontPx,
                    lineHeight: 1.2,
                    zIndex: selectedEl ? 30 : 10,
                  }}
                  onPointerDown={(e) => startDrag(e, el, 'move')}
                >
                  <div
                    className={`w-full h-full flex items-center overflow-hidden ${bubbleClass(el.bubble.preset)}`}
                    style={{ textAlign: el.style.align as 'left' | 'center' | 'right', padding: `${fontPx * 0.25}px ${fontPx * 0.4}px` }}
                  >
                    <span className="w-full" style={{ fontWeight: el.type === 'SFX' ? 700 : 400 }}>{el.text}</span>
                  </div>
                  {/* 尾巴 */}
                  {el.bubble.tail && (
                    <svg
                      className="absolute cursor-move"
                      style={{
                        left: `${el.bubble.tail.x * 100}%`,
                        top: `${el.bubble.tail.y * 100}%`,
                        transform: 'translate(-50%,-100%)',
                        width: fontPx * 0.9,
                        height: fontPx * 0.9,
                        zIndex: 1,
                      }}
                      viewBox="0 0 10 10"
                      onPointerDown={(e) => startDrag(e, el, 'tail')}
                    >
                      <polygon points="0,0 10,0 5,10" fill="white" stroke="#374151" strokeWidth="0.8" />
                    </svg>
                  )}
                  {/* 缩放手柄 */}
                  {selectedEl && (
                    <div
                      className="absolute -bottom-1 -right-1 w-3 h-3 bg-indigo-500 border border-white rounded-sm cursor-nwse-resize"
                      onPointerDown={(e) => startDrag(e, el, 'resize')}
                    />
                  )}
                  {selectedEl && (
                    <div className="absolute -top-5 left-0 text-[10px] bg-indigo-500 text-white px-1 rounded">{el.uid}</div>
                  )}
                  {selectedEl && (
                    <div className="absolute inset-0 border-2 border-dashed border-indigo-400 pointer-events-none rounded" />
                  )}
                </div>
              );
            })}
          </div>
        </div>

        {/* 编辑面板 */}
        <div className="flex flex-col gap-2">
          {selected ? (
            <>
              <Space wrap>
                <Select size="small" style={{ width: 90 }} value={selected.type}
                  onChange={(v) => updateSelected({ type: v })}
                  options={ELEMENT_TYPES} />
                <Popconfirm title="删除该文本框?" onConfirm={deleteSelected}>
                  <Button size="small" danger>删除</Button>
                </Popconfirm>
              </Space>
              <Input size="small" placeholder="说话人" value={selected.speaker ?? ''}
                onChange={(e) => updateSelected({ speaker: e.target.value })} />
              <Input.TextArea rows={3} value={selected.text}
                onChange={(e) => updateSelected({ text: e.target.value })} />
              <div>
                <Typography.Text type="secondary" className="text-xs">字号比例 {selected.style.fontSizeRatio.toFixed(3)}</Typography.Text>
                <Slider min={0.018} max={0.05} step={0.002} value={selected.style.fontSizeRatio}
                  onChange={(v) => updateSelected({ style: { ...selected.style, fontSizeRatio: v } })} />
              </div>
              <div>
                <Typography.Text type="secondary" className="text-xs">气泡样式</Typography.Text>
                <Select size="small" style={{ width: '100%' }} value={selected.bubble.preset}
                  onChange={(v) => updateSelected({ bubble: { ...selected.bubble, preset: v } })}
                  options={BUBBLE_PRESETS} />
              </div>
              <div>
                <Typography.Text type="secondary" className="text-xs">文字对齐</Typography.Text>
                <Segmented block size="small" value={selected.style.align}
                  onChange={(v) => updateSelected({ style: { ...selected.style, align: v as string } })}
                  options={['LEFT', 'CENTER', 'RIGHT']} />
              </div>
              <div>
                <Typography.Text type="secondary" className="text-xs">最大行数</Typography.Text>
                <Select size="small" style={{ width: '100%' }} value={selected.style.maxLines ?? 4}
                  onChange={(v) => updateSelected({ style: { ...selected.style, maxLines: v } })}
                  options={[2, 3, 4, 5, 6].map((n) => ({ value: n, label: `${n} 行` }))} />
              </div>
            </>
          ) : (
            <Typography.Text type="secondary" className="text-xs">
              点击画布中的气泡或左侧列表进行编辑;拖动移动,右下角手柄缩放,三角形为尾指(指向说话角色)。
            </Typography.Text>
          )}
          <div>
            <Typography.Text type="secondary" className="text-xs">预览尺寸</Typography.Text>
            <Radio.Group size="small" value={device} onChange={(e) => setDevice(e.target.value)} optionType="button"
              options={DEVICES} />
          </div>
          <Space wrap>
            <Button type="primary" size="small" icon={<SaveOutlined />} loading={save.isPending}
              disabled={!dirty} onClick={() => save.mutate()}>保存</Button>
            <Popconfirm title="清空当前布局并按脚本重新生成?" onConfirm={() => doReset.mutate()}>
              <Button size="small" icon={<ClearOutlined />}>恢复默认</Button>
            </Popconfirm>
            <Button size="small" icon={<SyncOutlined />} onClick={() => doSync.mutate()} loading={doSync.isPending}>
              同步脚本
            </Button>
            {dirty && <Tag color="orange" bordered={false}>未保存</Tag>}
          </Space>
        </div>
      </div>
    </div>
  );
}

function bubbleClass(preset: string) {
  switch (preset) {
    case 'NARRATION_BOX':
      return 'bg-amber-50/95 border border-gray-700 rounded';
    case 'THOUGHT_SIMPLE':
      return 'bg-white/95 border-2 border-dashed border-gray-500 rounded-[2em]';
    case 'SFX':
      return 'bg-transparent';
    default:
      return 'bg-white border-2 border-gray-800 rounded-[2em] shadow-sm';
  }
}

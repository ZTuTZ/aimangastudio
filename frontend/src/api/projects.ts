import { http, unwrap } from './http';
import type { TaskVO } from './tasks';

export type ColorMode = 'partial' | 'monochrome' | 'color';
export type AspectRatio = '3:4' | '2:3' | '1:1' | '16:9';

export interface ProjectVO {
  id: number;
  contentUid: string;
  title: string;
  status: number; // 0准备中 1待出图 2出图中 3完成 4部分失败
  aspectRatio: string;
  /** 素材参考图画幅:场景(默认 16:9) */
  sceneRatio: string | null;
  /** 素材参考图画幅:道具(默认 1:1) */
  propRatio: string | null;
  /** 素材参考图画幅:服装(默认 3:4) */
  costumeRatio: string | null;
  colorMode: string;
  stylePresetId: number | null;
  tagline: string | null;
  description: string | null;
  coverUrl: string | null;
  category: string | null;
  tags: string | null; // JSON 数组字符串,如 ["重生","系统"]
  seriesStatus: number | null; // 1连载中 2已完结
  sourceText?: string | null;
  createTime: string;
  updateTime: string;
  chapterCount: number;
  pageCount: number;
  pauseRequested?: boolean;
}

/** 连载状态 */
export const SERIES_STATUS: Record<number, { label: string; color: string }> = {
  1: { label: '连载中', color: 'blue' },
  2: { label: '已完结', color: 'green' },
};

/** SPLIT 元数据建议分类(允许自定义输入) */
export const CATEGORY_SUGGESTIONS = ['古风', '都市', '恋爱', '悬疑', '科幻', '奇幻', '热血', '搞笑', '治愈', '校园', '其他'];

export interface ChapterVO {
  id: number;
  projectId: number;
  chapterNo: number;
  title: string;
  scriptText: string | null;
  status: number;
  pageCount: number;
  updateTime: string;
}

export interface PageVO {
  id: number;
  chapterId: number;
  pageNo: number;
  narration: string | null;
  dialogue: string | null;
  visual: string | null;
  sceneDescription: string | null;
  layoutImageUrl: string | null;
  generatedImageUrl: string | null;
  colorMode: string | null;
  generateStatus: number;
  failReason: string | null;
  scriptVersion?: number | null;
  layoutScriptVersion?: number | null;
  imageScriptVersion?: number | null;
  generateRecords?: string | null;
}

export interface AssetVO {
  id: number;
  assetType: number; // 1角色 2场景 3道具 4服装
  name: string;
  aliases: string | null;
  description: string | null;
  structured: string | null;
  referenceUrl: string | null;
  sheetImageUrl: string | null;
  genStatus: number;
}

export interface PresetVO {
  id: number;
  name: string;
  positivePrompt: string;
  colorMode: string | null;
  remark: string | null;
}

export interface ProjectSettings {
  aspectRatio?: AspectRatio;
  colorMode?: ColorMode;
  stylePresetId?: number | null;
  /** 素材参考图画幅:场景(默认 16:9) */
  sceneRatio?: string;
  /** 素材参考图画幅:道具(默认 1:1) */
  propRatio?: string;
  /** 素材参考图画幅:服装(默认 3:4) */
  costumeRatio?: string;
}

export interface PageResult<T> {
  records: T[];
  total: number;
}

export interface ProjectListParams {
  page: number;
  size: number;
  keyword?: string;
  status?: number;
}

export const projectsApi = {
  list: (params: ProjectListParams) => unwrap<PageResult<ProjectVO>>(http.get('/projects', { params })),
  get: (id: number) => unwrap<ProjectVO>(http.get(`/projects/${id}`)),
  create: (data: { title: string; sourceText: string } & ProjectSettings) =>
    unwrap<ProjectVO>(http.post('/projects', data)),
  importFiles: (files: File[], settings: ProjectSettings) => {
    const form = new FormData();
    files.forEach((f) => form.append('files', f));
    if (settings.aspectRatio) form.append('aspectRatio', settings.aspectRatio);
    if (settings.colorMode) form.append('colorMode', settings.colorMode);
    if (settings.stylePresetId) form.append('stylePresetId', String(settings.stylePresetId));
    if (settings.sceneRatio) form.append('sceneRatio', settings.sceneRatio);
    if (settings.propRatio) form.append('propRatio', settings.propRatio);
    if (settings.costumeRatio) form.append('costumeRatio', settings.costumeRatio);
    return unwrap<ProjectVO[]>(http.post('/projects/import', form, { timeout: 120000 }));
  },
  update: (
    id: number,
    data: Partial<{
      title: string;
      aspectRatio: string;
      colorMode: string;
      sceneRatio: string;
      propRatio: string;
      costumeRatio: string;
      stylePresetId: number | null;
      tagline: string;
      description: string;
      coverUrl: string;
      category: string;
      tags: string;
      seriesStatus: number;
    }>,
  ) => unwrap<ProjectVO>(http.put(`/projects/${id}`, data)),
  remove: (id: number) => unwrap<void>(http.delete(`/projects/${id}`)),
  split: (id: number) => unwrap<TaskVO>(http.post(`/projects/${id}/split`)),
  rebuildAssets: (id: number) => unwrap<TaskVO>(http.post(`/projects/${id}/rebuild-assets`)),
  regenerateScript: (chapterId: number) => unwrap<TaskVO>(http.post(`/chapters/${chapterId}/regenerate-script`)),
  generateSheet: (assetId: number) => unwrap<TaskVO>(http.post(`/assets/${assetId}/generate-sheet`)),
  generationPreflight: (projectId: number, opts?: { chapterId?: number; chapterIds?: number[] }) => {
    const params: Record<string, unknown> = {};
    if (opts?.chapterId) params.chapterId = opts.chapterId;
    if (opts?.chapterIds?.length) params.chapterIds = opts.chapterIds.join(',');
    return unwrap<PreflightResult>(http.get(`/projects/${projectId}/generation-preflight`, { params }));
  },
  rebuildPageAssetRefs: (projectId: number) =>
    unwrap<number>(http.post(`/projects/${projectId}/page-asset-refs/rebuild`)),
  pipelineStages: (projectId: number) =>
    unwrap<PipelineStageVO[]>(http.get(`/projects/${projectId}/pipeline`)),
  generateBatch: (projectId: number, data: {
    scope: 'PROJECT' | 'CHAPTER' | 'CHAPTERS';
    chapterId?: number;
    chapterIds?: number[];
    colorMode?: string;
    skipGenerated?: boolean;
    forceLayout?: boolean;
    forceImage?: boolean;
  }) => unwrap<TaskVO>(http.post(`/projects/${projectId}/generate-batch`, data)),
  pausePipeline: (projectId: number) => unwrap<void>(http.post(`/projects/${projectId}/pause`)),
  resumePipeline: (projectId: number) => unwrap<void>(http.post(`/projects/${projectId}/resume`)),
  /** 批量生成勾选资产素材图:角色→设定表,场景/道具/服装→参考图(混选时可能返回多个任务) */
  generateForAssets: (projectId: number, assetIds: number[]) =>
    unwrap<TaskVO[]>(http.post(`/projects/${projectId}/assets/generate`, { assetIds })),
  chapters: (projectId: number) => unwrap<ChapterVO[]>(http.get(`/projects/${projectId}/chapters`)),
  createChapter: (projectId: number, data: { title?: string; scriptText?: string }) =>
    unwrap<ChapterVO>(http.post(`/projects/${projectId}/chapters`, data)),
  updateChapter: (id: number, data: { title?: string; scriptText?: string }) =>
    unwrap<ChapterVO>(http.put(`/chapters/${id}`, data)),
  pages: (chapterId: number) => unwrap<PageVO[]>(http.get(`/chapters/${chapterId}/pages`)),
  updatePage: (id: number, data: { narration?: string; dialogue?: string; visual?: string; sceneDescription?: string }) =>
    unwrap<PageVO>(http.put(`/pages/${id}`, data)),
  page: (id: number) => unwrap<PageVO>(http.get(`/pages/${id}`)),
  generatePageLayout: (id: number) => unwrap<TaskVO>(http.post(`/pages/${id}/generate-layout`)),
  generatePageImage: (id: number, colorMode?: string) =>
    unwrap<TaskVO>(http.post(`/pages/${id}/generate`, colorMode ? { colorMode } : {})),
  colorizePage: (id: number, colorMode?: string) =>
    unwrap<TaskVO>(http.post(`/pages/${id}/colorize`, colorMode ? { colorMode } : {})),
  cleanPage: (id: number) => unwrap<TaskVO>(http.post(`/pages/${id}/clean`)),
  repaintPage: (id: number, data: { repaintPrompt: string; maskUrl: string }) =>
    unwrap<TaskVO>(http.post(`/pages/${id}/repaint`, data)),
  generationRecords: (id: number) => unwrap<GenerationRecordVO[]>(http.get(`/pages/${id}/generation-records`)),
  textLayer: (id: number) => unwrap<TextLayerDto>(http.get(`/pages/${id}/text-layer`)),
  initializeTextLayer: (id: number) => unwrap<TextLayerDto>(http.post(`/pages/${id}/text-layer/initialize`)),
  saveTextLayer: (id: number, dto: TextLayerDto) => unwrap<TextLayerDto>(http.put(`/pages/${id}/text-layer`, dto)),
  resetTextLayer: (id: number) => unwrap<TextLayerDto>(http.post(`/pages/${id}/text-layer/reset`)),
  syncTextLayer: (id: number) => unwrap<TextLayerDto>(http.post(`/pages/${id}/text-layer/sync`)),
  assets: (projectId: number) => unwrap<AssetVO[]>(http.get(`/projects/${projectId}/assets`)),
  createAsset: (projectId: number, data: AssetPayload) =>
    unwrap<AssetVO>(http.post(`/projects/${projectId}/assets`, data)),
  updateAsset: (id: number, data: Partial<AssetPayload>) =>
    unwrap<AssetVO>(http.put(`/assets/${id}`, data)),
  removeAsset: (id: number) => unwrap<void>(http.delete(`/assets/${id}`)),
  uploadFile: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return unwrap<{ url: string }>(http.post('/files/upload', form, { timeout: 120000 }));
  },
  presets: () => unwrap<PresetVO[]>(http.get('/presets')),
};

export interface AssetPayload {
  assetType: number;
  name: string;
  aliases?: string;
  description?: string;
  structured?: string;
  referenceUrl?: string;
}

export const ASSET_TYPE_NAMES: Record<number, string> = {
  1: '角色',
  2: '场景',
  3: '道具',
  4: '服装',
};

export const PROJECT_STATUS: Record<number, { label: string; color: string }> = {
  0: { label: '准备中', color: 'cyan' },
  1: { label: '待出图', color: 'blue' },
  2: { label: '出图中', color: 'geekblue' },
  3: { label: '完成', color: 'green' },
  4: { label: '部分失败', color: 'orange' },
};

/** 出图素材预检(Phase 6.1) */
export interface PreflightAsset {
  id: number;
  name: string;
  assetType: number;
}

export interface PreflightTypeStat {
  assetType: number;
  total: number;
  ready: number;
}

export interface PreflightResult {
  ready: boolean;
  pageCount: number;
  requiredAssets: number;
  readyAssets: number;
  missingRequiredAssets: PreflightAsset[];
  optionalMissingAssets: PreflightAsset[];
  stats: PreflightTypeStat[];
  warnings: string[];
}

/** 流水线阶段进度(含 Phase 5.9/6 的 Item 统计) */
export interface PipelineStageVO {
  id: number;
  projectId: number;
  stageType: string;
  status: number; // 0排队 1进行中 2成功 3失败 4暂停 5停止
  progress: number;
  totalCount: number | null;
  successCount: number | null;
  failedCount: number | null;
  error: string | null;
  startTime: string | null;
  finishTime: string | null;
}

/** AI 生成记录(Phase 6.7) */
export interface GenerationRecordVO {
  id: number;
  kind: string;
  model: string;
  prompt: string | null;
  referenceUrls: string | null;
  inputUrl: string | null;
  resultUrl: string | null;
  status: string;
  error: string | null;
  createTime: string;
}

// ---------- Comic Text Layer(Phase 7.8,comic-text-layer-1.0) ----------

export interface TextLayerPosition {
  x: number;
  y: number;
  width: number;
  height: number | null;
}

export interface TextLayerStyle {
  fontPreset: string;
  fontSizeRatio: number;
  align: string;
  maxLines: number | null;
}

export interface TextLayerBubble {
  preset: string;
  tail: { x: number; y: number } | null;
}

export interface TextLayerElement {
  uid: string;
  type: 'DIALOGUE' | 'NARRATION' | 'THOUGHT' | 'SFX';
  dialogueIndex: number | null;
  speaker: string | null;
  text: string;
  position: TextLayerPosition;
  style: TextLayerStyle;
  bubble: TextLayerBubble;
  sortOrder: number;
}

export interface TextLayerDto {
  schemaVersion: string;
  pageId: number;
  pageVersion: number | null;
  textLayoutSourceVersion?: number | null;
  elements: TextLayerElement[];
}

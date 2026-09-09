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
    return unwrap<ProjectVO[]>(http.post('/projects/import', form, { timeout: 120000 }));
  },
  update: (
    id: number,
    data: Partial<{
      title: string;
      aspectRatio: string;
      colorMode: string;
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
  chapters: (projectId: number) => unwrap<ChapterVO[]>(http.get(`/projects/${projectId}/chapters`)),
  createChapter: (projectId: number, data: { title?: string; scriptText?: string }) =>
    unwrap<ChapterVO>(http.post(`/projects/${projectId}/chapters`, data)),
  updateChapter: (id: number, data: { title?: string; scriptText?: string }) =>
    unwrap<ChapterVO>(http.put(`/chapters/${id}`, data)),
  pages: (chapterId: number) => unwrap<PageVO[]>(http.get(`/chapters/${chapterId}/pages`)),
  updatePage: (id: number, data: { narration?: string; dialogue?: string; visual?: string; sceneDescription?: string }) =>
    unwrap<PageVO>(http.put(`/pages/${id}`, data)),
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

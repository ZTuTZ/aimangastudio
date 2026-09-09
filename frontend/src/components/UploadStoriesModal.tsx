import { App, Modal, Select, Space, Typography, Upload } from 'antd';
import { CloudUploadOutlined, FileTextOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { projectsApi, type AspectRatio, type ColorMode, type ProjectSettings } from '@/api/projects';

interface UploadStoriesModalProps {
  open: boolean;
  onClose: () => void;
}

/** 批量上传导入:多文件 TXT/DOCX 或 粘贴文本;创建即自动启动第一步流水线(任务系统上线后生效) */
export function UploadStoriesModal({ open, onClose }: UploadStoriesModalProps) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<'file' | 'paste'>('file');
  const [files, setFiles] = useState<File[]>([]);
  const [title, setTitle] = useState('');
  const [sourceText, setSourceText] = useState('');
  const [settings, setSettings] = useState<ProjectSettings>({ aspectRatio: '3:4', colorMode: 'partial' });
  const [submitting, setSubmitting] = useState(false);

  const { data: presets } = useQuery({
    queryKey: ['presets'],
    queryFn: projectsApi.presets,
    enabled: open,
  });

  const reset = () => {
    setFiles([]);
    setTitle('');
    setSourceText('');
    setSettings({ aspectRatio: '3:4', colorMode: 'partial' });
  };

  const close = () => {
    reset();
    onClose();
  };

  const submit = async () => {
    setSubmitting(true);
    try {
      if (mode === 'file') {
        if (files.length === 0) {
          message.warning('请先选择要导入的文件');
          return;
        }
        const created = await projectsApi.importFiles(files, settings);
        message.success(`已导入 ${created.length} 部作品,准备流水线已自动开始(拆话→脚本→资产→设定表)`);
      } else {
        if (!title.trim() || !sourceText.trim()) {
          message.warning('请填写标题与故事原文');
          return;
        }
        await projectsApi.create({ title: title.trim(), sourceText: sourceText.trim(), ...settings });
        message.success('作品已创建,准备流水线已自动开始(拆话→脚本→资产→设定表)');
      }
      queryClient.invalidateQueries({ queryKey: ['projects'] });
      close();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导入失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="上传故事"
      width={560}
      okText={mode === 'file' ? '导入' : '创建'}
      cancelText="取消"
      confirmLoading={submitting}
      onCancel={close}
      onOk={submit}
      destroyOnHidden
    >
      <Space className="mb-4">
        <Select
          value={mode}
          style={{ width: 140 }}
          onChange={(v) => setMode(v)}
          options={[
            { value: 'file', label: '上传文件' },
            { value: 'paste', label: '粘贴文本' },
          ]}
        />
        <Typography.Text type="secondary">每个文件/文本 = 一部作品,可同时导入多部</Typography.Text>
      </Space>

      {mode === 'file' ? (
        <Upload.Dragger
          multiple
          accept=".txt,.docx"
          maxCount={20}
          fileList={undefined}
          beforeUpload={(_, fileList) => {
            setFiles(fileList);
            return false;
          }}
          onRemove={(file) => setFiles((prev) => prev.filter((f) => f !== file.originFileObj))}
          showUploadList
        >
          <p className="ant-upload-drag-icon">
            <CloudUploadOutlined />
          </p>
          <p className="ant-upload-text">点击或拖拽 TXT / DOCX 文件到此处</p>
          <p className="ant-upload-hint">支持一次选择多个文件,文件名将作为作品标题</p>
        </Upload.Dragger>
      ) : (
        <div className="flex flex-col gap-3">
          <Typography.Text type="secondary">
            <FileTextOutlined /> 标题与故事原文
          </Typography.Text>
          <input
            className="border border-gray-200 rounded-md px-3 py-2 text-sm"
            placeholder="作品标题"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
          <textarea
            className="border border-gray-200 rounded-md px-3 py-2 text-sm min-h-40"
            placeholder="粘贴故事原文…"
            value={sourceText}
            onChange={(e) => setSourceText(e.target.value)}
          />
        </div>
      )}

      <div className="mt-4 grid grid-cols-3 gap-3">
        <div>
          <Typography.Text type="secondary" className="block mb-1 text-xs">画幅</Typography.Text>
          <Select<AspectRatio>
            style={{ width: '100%' }}
            value={settings.aspectRatio}
            onChange={(v) => setSettings((s) => ({ ...s, aspectRatio: v }))}
            options={[
              { value: '3:4', label: '3:4 竖版' },
              { value: '2:3', label: '2:3 竖版' },
              { value: '1:1', label: '1:1 方形' },
              { value: '16:9', label: '16:9 横版' },
            ]}
          />
        </div>
        <div>
          <Typography.Text type="secondary" className="block mb-1 text-xs">色彩模式</Typography.Text>
          <Select<ColorMode>
            style={{ width: '100%' }}
            value={settings.colorMode}
            onChange={(v) => setSettings((s) => ({ ...s, colorMode: v }))}
            options={[
              { value: 'partial', label: '局部上色' },
              { value: 'monochrome', label: '黑白' },
              { value: 'color', label: '全彩' },
            ]}
          />
        </div>
        <div>
          <Typography.Text type="secondary" className="block mb-1 text-xs">风格预设</Typography.Text>
          <Select
            style={{ width: '100%' }}
            allowClear
            placeholder="默认"
            value={settings.stylePresetId ?? undefined}
            onChange={(v) => setSettings((s) => ({ ...s, stylePresetId: v ?? null }))}
            options={(presets ?? []).map((p) => ({ value: p.id, label: p.name }))}
          />
        </div>
      </div>
    </Modal>
  );
}

import { App, Button, Card, Input, Select, Space, Tabs, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { configApi, type ConfigMap } from '@/api/config';

interface FieldDef {
  key: string;
  label: string;
  type?: 'input' | 'password' | 'number' | 'select';
  placeholder?: string;
  options?: { value: string; label: string }[];
}

interface GroupDef {
  key: string;
  title: string;
  fields: FieldDef[];
}

const GROUPS: GroupDef[] = [
  {
    key: 'ai_text',
    title: 'AI 文本通道(拆话/脚本/资产提取)',
    fields: [
      { key: 'ai_text_api_url', label: '接口地址' },
      { key: 'ai_text_api_key', label: 'API Key', type: 'password', placeholder: '已配置(留空保持不变)' },
      { key: 'ai_text_model', label: '模型' },
      { key: 'ai_text_protocol', label: '协议', type: 'select', options: [
        { value: 'gemini', label: 'gemini(generateContent,GeekAI 网关)' },
        { value: 'openai', label: 'openai(chat.completions,GLM/DeepSeek)' },
      ] },
      { key: 'ai_text_timeout', label: '超时(毫秒)', type: 'number' },
      { key: 'ai_text_concurrency', label: '并发上限', type: 'number' },
    ],
  },
  {
    key: 'ai_image',
    title: 'AI 生图通道(布局图/成品页/设定表)',
    fields: [
      { key: 'ai_image_api_url', label: '接口地址' },
      { key: 'ai_image_api_key', label: 'API Key', type: 'password', placeholder: '已配置(留空保持不变)' },
      { key: 'ai_image_model', label: '模型' },
      { key: 'ai_image_protocol', label: '协议', type: 'select', options: [
        { value: 'gemini', label: 'gemini(generateContent)' },
        { value: 'openai', label: 'openai(images.generations)' },
      ] },
      { key: 'ai_image_timeout', label: '超时(毫秒)', type: 'number' },
      { key: 'ai_image_concurrency', label: '并发上限', type: 'number' },
    ],
  },
  {
    key: 'ai_merge',
    title: 'AI 编辑通道(上色/清晰化/局部重绘)',
    fields: [
      { key: 'ai_merge_api_url', label: '接口地址' },
      { key: 'ai_merge_api_key', label: 'API Key', type: 'password', placeholder: '已配置(留空保持不变)' },
      { key: 'ai_merge_model', label: '模型' },
      { key: 'ai_merge_protocol', label: '协议', type: 'select', options: [
        { value: 'gemini', label: 'gemini(generateContent)' },
        { value: 'openai', label: 'openai(chat.completions)' },
      ] },
      { key: 'ai_merge_timeout', label: '超时(毫秒)', type: 'number' },
      { key: 'ai_merge_concurrency', label: '并发上限', type: 'number' },
    ],
  },
  {
    key: 'task',
    title: '任务并发(四层控制)',
    fields: [
      { key: 'task_max_concurrency', label: '全局并行任务数(Worker)', type: 'number' },
      { key: 'task_user_concurrency', label: '每用户并行任务数', type: 'number' },
      { key: 'storyboard_page_count', label: '脚本默认页数', type: 'number' },
    ],
  },
  {
    key: 'stage_pools',
    title: '阶段并发池(Phase 5.11,热更新)',
    fields: [
      { key: 'script_item_concurrency', label: '脚本阶段并发数(1-32)', type: 'number' },
      { key: 'script_queue_size', label: '脚本池队列容量(重启生效)', type: 'number' },
      { key: 'image_generation_concurrency', label: '生图阶段并发数(1-32)', type: 'number' },
      { key: 'image_gen_max_retry', label: '单图失败重试次数', type: 'number' },
      { key: 'image_queue_size', label: '生图池队列容量(重启生效)', type: 'number' },
    ],
  },
  {
    key: 'oss',
    title: '阿里云 OSS(所有图片统一转存)',
    fields: [
      { key: 'oss_access_key', label: 'AccessKeyId', type: 'password', placeholder: '已配置(留空保持不变)' },
      { key: 'oss_access_secret', label: 'AccessKeySecret', type: 'password', placeholder: '已配置(留空保持不变)' },
      { key: 'oss_bucket', label: 'Bucket' },
      { key: 'oss_region', label: 'Region' },
      { key: 'oss_endpoint', label: '自定义 Endpoint(可空)' },
      { key: 'oss_image_process', label: '缩略图处理参数(可空)' },
    ],
  },
];

export function SystemConfig() {
  const { message } = App.useApp();
  const { data, isLoading } = useQuery({ queryKey: ['configs'], queryFn: configApi.list });
  const [values, setValues] = useState<ConfigMap>({});
  const [saving, setSaving] = useState(false);
  const [verifying, setVerifying] = useState<'text' | 'image' | null>(null);

  useEffect(() => {
    if (data) {
      setValues(data);
    }
  }, [data]);

  const secretMasked = (key: string) => values[key] === '***';

  const onSave = async () => {
    setSaving(true);
    try {
      const saved = await configApi.save(values);
      setValues(saved);
      message.success('配置已保存并热更新生效');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const onVerify = async (kind: 'text' | 'image') => {
    setVerifying(kind);
    try {
      const result = await configApi.verify(kind);
      if (result.ok) {
        message.success(result.message);
      } else {
        message.error(result.message);
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '验证失败');
    } finally {
      setVerifying(null);
    }
  };

  const renderGroup = (group: GroupDef) => (
    <div className="flex flex-col gap-3 max-w-2xl">
      {group.fields.map((field) => (
        <div key={field.key} className="grid grid-cols-[180px_1fr] items-center gap-3">
          <Typography.Text type="secondary" className="text-sm">
            {field.label}
          </Typography.Text>
          {field.type === 'password' ? (
            <Input.Password
              value={secretMasked(field.key) ? '' : values[field.key] ?? ''}
              placeholder={secretMasked(field.key) ? '已配置,留空保持不变' : '未配置'}
              onChange={(e) => setValues((v) => ({ ...v, [field.key]: e.target.value }))}
              autoComplete="new-password"
            />
          ) : field.type === 'select' ? (
            <Select
              value={values[field.key] || undefined}
              placeholder="未配置(默认 gemini)"
              options={field.options}
              onChange={(v) => setValues((x) => ({ ...x, [field.key]: v }))}
            />
          ) : (
            <Input
              value={values[field.key] ?? ''}
              placeholder={field.placeholder}
              onChange={(e) => setValues((v) => ({ ...v, [field.key]: e.target.value }))}
            />
          )}
        </div>
      ))}
      {(group.key === 'ai_text' || group.key === 'ai_image') && (
        <div className="grid grid-cols-[180px_1fr] items-center gap-3">
          <span />
          <Button
            loading={verifying === (group.key === 'ai_text' ? 'text' : 'image')}
            onClick={() => onVerify(group.key === 'ai_text' ? 'text' : 'image')}
          >
            测试连通(真实调用一次)
          </Button>
        </div>
      )}
    </div>
  );

  return (
    <Card
      title="系统配置"
      extra={
        <Space>
          <Typography.Text type="secondary" className="text-xs">
            密钥保存后脱敏显示;修改即时生效,无需重启
          </Typography.Text>
          <Button type="primary" loading={saving} onClick={onSave} disabled={isLoading}>
            保存全部
          </Button>
        </Space>
      }
    >
      <Tabs
        items={GROUPS.map((group) => ({
          key: group.key,
          label: group.title.split('(')[0],
          children: (
            <div>
              <Typography.Title level={5}>{group.title}</Typography.Title>
              {renderGroup(group)}
            </div>
          ),
        }))}
      />
    </Card>
  );
}

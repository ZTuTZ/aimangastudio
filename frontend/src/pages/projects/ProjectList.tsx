import { App, Card, Empty, Popconfirm, Tag, Typography } from 'antd';
import { PlusOutlined, UploadOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { projectsApi, PROJECT_STATUS, type ProjectVO } from '@/api/projects';
import { UploadStoriesModal } from '@/components/UploadStoriesModal';

const GRADIENTS = [
  'linear-gradient(135deg,#6366f1,#a855f7)',
  'linear-gradient(135deg,#0ea5e9,#6366f1)',
  'linear-gradient(135deg,#f59e0b,#ef4444)',
  'linear-gradient(135deg,#10b981,#0ea5e9)',
  'linear-gradient(135deg,#ec4899,#f59e0b)',
];

export function ProjectList() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [uploadOpen, setUploadOpen] = useState(false);
  const { data, isLoading } = useQuery({ queryKey: ['projects'], queryFn: projectsApi.list });

  const onDelete = async (project: ProjectVO) => {
    try {
      await projectsApi.remove(project.id);
      message.success(`已删除「${project.title}」`);
      queryClient.invalidateQueries({ queryKey: ['projects'] });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败');
    }
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-5">
        <div>
          <Typography.Title level={4} style={{ margin: 0 }}>
            作品库
          </Typography.Title>
          <Typography.Text type="secondary">上传故事 → 准备流水线 → 一键生成成品页</Typography.Text>
        </div>
        <button
          onClick={() => setUploadOpen(true)}
          className="flex items-center gap-2 px-4 py-2 rounded-lg text-white text-sm font-semibold shadow-sm hover:opacity-90 transition"
          style={{ background: 'linear-gradient(135deg,#6366f1,#8b5cf6)' }}
        >
          <UploadOutlined /> 上传故事
        </button>
      </div>

      {data && data.length === 0 && !isLoading && (
        <Card>
          <Empty description="还没有作品,点击右上角「上传故事」开始创作">
            <button
              onClick={() => setUploadOpen(true)}
              className="flex items-center gap-2 px-4 py-2 rounded-lg text-white text-sm font-semibold"
              style={{ background: '#6366f1' }}
            >
              <PlusOutlined /> 上传第一部作品
            </button>
          </Empty>
        </Card>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
        {(data ?? []).map((project, idx) => {
          const status = PROJECT_STATUS[project.status] ?? { label: '未知', color: 'default' };
          return (
            <Card
              key={project.id}
              hoverable
              className="overflow-hidden"
              styles={{ body: { padding: 0 } }}
              onClick={() => navigate(`/projects/${project.id}`)}
            >
              <div
                className="h-32 flex items-center justify-center text-white text-4xl font-bold"
                style={{ background: GRADIENTS[idx % GRADIENTS.length] }}
              >
                {project.title.slice(0, 1)}
              </div>
              <div className="p-4">
                <div className="flex items-center justify-between gap-2">
                  <Typography.Text strong ellipsis className="flex-1">
                    {project.title}
                  </Typography.Text>
                  <Tag color={status.color}>{status.label}</Tag>
                </div>
                <Typography.Paragraph type="secondary" className="mt-1 mb-2 text-xs" ellipsis={{ rows: 1 }}>
                  {project.tagline || '暂无简介'}
                </Typography.Paragraph>
                <div className="flex items-center justify-between text-xs text-gray-400">
                  <span>
                    {project.aspectRatio} · {project.colorMode === 'partial' ? '局部上色' : project.colorMode === 'monochrome' ? '黑白' : '全彩'} ·{' '}
                    {project.chapterCount} 话 {project.pageCount} 页
                  </span>
                  <Popconfirm
                    title={`删除「${project.title}」?`}
                    description="话/页/资产将一并删除,不可恢复"
                    okText="删除"
                    okButtonProps={{ danger: true }}
                    cancelText="取消"
                    onPopupClick={(e) => e.stopPropagation()}
                    onConfirm={(e) => {
                      e?.stopPropagation();
                      onDelete(project);
                    }}
                    onCancel={(e) => e?.stopPropagation()}
                  >
                    <button
                      className="text-xs text-gray-400 hover:text-red-500"
                      onClick={(e) => e.stopPropagation()}
                    >
                      删除
                    </button>
                  </Popconfirm>
                </div>
              </div>
            </Card>
          );
        })}
      </div>

      <UploadStoriesModal open={uploadOpen} onClose={() => setUploadOpen(false)} />
    </div>
  );
}

import { Alert, Button, Card, Table, Tag, Typography } from 'antd';
import { CheckCircleOutlined, ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App } from 'antd';
import { ASSET_TYPE_NAMES, projectsApi, type PreflightResult } from '@/api/projects';

/**
 * 生成成品 —— 出图预检(Phase 6.1 T6.1.6):
 * 启动正式出图前先看素材准备情况;缺必需角色不允许开始(Phase 6.3 的 BATCH 会强制校验),
 * 场景/道具/服装缺图仅提示。完整生成控制台在 Phase 6.4 落地。
 */
export function GenerationPreflightTab({ projectId, onGoAssets }: {
  projectId: number;
  onGoAssets: () => void;
}) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const { data, isLoading } = useQuery({
    queryKey: ['preflight', projectId],
    queryFn: () => projectsApi.generationPreflight(projectId),
  });

  const rebuild = useMutation({
    mutationFn: () => projectsApi.rebuildPageAssetRefs(projectId),
    onSuccess: (count) => {
      message.success(`已重建绑定,共 ${count} 条页-素材引用`);
      queryClient.invalidateQueries({ queryKey: ['preflight', projectId] });
    },
    onError: (e) => message.error(e instanceof Error ? e.message : '重建失败'),
  });

  if (isLoading || !data) {
    return <Card loading />;
  }
  return <PreflightView result={data} onGoAssets={onGoAssets} onRebuild={() => rebuild.mutate()} rebuilding={rebuild.isPending} />;
}

function PreflightView({ result, onGoAssets, onRebuild, rebuilding }: {
  result: PreflightResult;
  onGoAssets: () => void;
  onRebuild: () => void;
  rebuilding: boolean;
}) {
  return (
    <div className="flex flex-col gap-4 max-w-3xl">
      <Alert
        type={result.ready ? 'success' : 'warning'}
        showIcon
        icon={result.ready ? <CheckCircleOutlined /> : <WarningOutlined />}
        message={result.ready
          ? `素材准备就绪,可以开始出图(共 ${result.pageCount} 页)`
          : '素材尚未就绪,请先补齐必需的角色参考图'}
        description={
          <div className="flex flex-col gap-1 text-xs">
            {result.warnings.map((w, i) => (
              <Typography.Text key={i} type="secondary">· {w}</Typography.Text>
            ))}
          </div>
        }
      />

      <Card size="small" title="素材准备情况" extra={
        <Button size="small" icon={<ReloadOutlined />} loading={rebuilding} onClick={onRebuild}>
          重建绑定
        </Button>
      }>
        {result.stats.length === 0 ? (
          <Typography.Text type="secondary" className="text-sm">
            还没有页-素材绑定数据:请确认脚本已生成,或点击「重建绑定」按现有脚本匹配。
          </Typography.Text>
        ) : (
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            {result.stats.map((s) => {
              const missing = s.total - s.ready;
              return (
                <div key={s.assetType} className="p-3 rounded-lg border border-gray-200 bg-white text-center">
                  <Typography.Text type="secondary" className="text-xs">
                    {ASSET_TYPE_NAMES[s.assetType] ?? '资产'}素材
                  </Typography.Text>
                  <div className="text-xl font-semibold mt-1">
                    {s.ready} <span className="text-sm text-gray-400">/ {s.total}</span>
                  </div>
                  {missing > 0 && (
                    <Tag color={s.assetType === 1 ? 'red' : 'orange'} bordered={false} className="mt-1">
                      缺 {missing}
                    </Tag>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </Card>

      {result.missingRequiredAssets.length > 0 && (
        <Card size="small" title={
          <span className="text-red-500">缺少必需角色参考图({result.missingRequiredAssets.length})</span>
        } extra={
          <Button type="primary" size="small" onClick={onGoAssets}>前往资产库</Button>
        }>
          <Table<PreflightResult['missingRequiredAssets'][number]>
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={result.missingRequiredAssets}
            columns={[
              { title: '资产', dataIndex: 'name' },
              { title: '类型', width: 90, render: (_, a) => ASSET_TYPE_NAMES[a.assetType] ?? a.assetType },
            ]}
          />
          <Typography.Text type="secondary" className="text-xs block mt-2">
            出图时角色需要设定表或参考图作为一致性依据;系统不会自动替你生成,请到资产库勾选后批量生成。
          </Typography.Text>
        </Card>
      )}

      {result.optionalMissingAssets.length > 0 && (
        <Card size="small" title={`可选素材缺参考图(${result.optionalMissingAssets.length})`}>
          <Typography.Text type="secondary" className="text-xs">
            {result.optionalMissingAssets.map((a) => a.name).join('、')} —— 出图将以文字设定生成,不影响开始。
          </Typography.Text>
        </Card>
      )}

      <Typography.Text type="secondary" className="text-xs">
        一键生成成品页(整部/按话 × 色彩模式)将在素材就绪后开放(Phase 6.3)。
      </Typography.Text>
    </div>
  );
}

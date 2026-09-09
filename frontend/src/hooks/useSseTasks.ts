import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@/stores/authStore';

/**
 * 任务事件 SSE 订阅:
 * - 实时接收 task.created/progress/status/page.generated,失效任务相关查询(列表/徽标/作品详情);
 * - EventSource 断线自动重连;返回连接状态(任务页据此降级轮询)。
 */
export function useSseTasks(enabled: boolean): boolean {
  const token = useAuthStore((s) => s.token);
  const queryClient = useQueryClient();
  const [connected, setConnected] = useState(false);
  const connectedRef = useRef(false);

  useEffect(() => {
    if (!enabled || !token) {
      setConnected(false);
      connectedRef.current = false;
      return;
    }
    const source = new EventSource(`/api/tasks/events?token=${encodeURIComponent(token)}`);
    source.onopen = () => {
      setConnected(true);
      connectedRef.current = true;
    };
    source.onerror = () => {
      setConnected(false);
      connectedRef.current = false;
    };
    const onEvent = (event: MessageEvent) => {
      try {
        const msg = JSON.parse(event.data);
        queryClient.invalidateQueries({ queryKey: ['tasks'] });
        queryClient.invalidateQueries({ queryKey: ['task-badge'] });
        if (msg.projectId) {
          queryClient.invalidateQueries({ queryKey: ['project', Number(msg.projectId)] });
          queryClient.invalidateQueries({ queryKey: ['pages'] });
        }
      } catch {
        // 忽略无法解析的心跳等消息
      }
    };
    source.addEventListener('task.created', onEvent);
    source.addEventListener('task.progress', onEvent);
    source.addEventListener('task.status', onEvent);
    source.addEventListener('page.generated', onEvent);
    return () => {
      source.close();
      setConnected(false);
      connectedRef.current = false;
    };
  }, [enabled, token, queryClient]);

  return connected;
}

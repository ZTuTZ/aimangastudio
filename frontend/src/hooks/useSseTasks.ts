import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@/stores/authStore';

let sharedSource: EventSource | null = null;
let refCount = 0;
let connectedRef = false;
const connectedListeners = new Set<(v: boolean) => void>();
let globalQueryClient: ReturnType<typeof useQueryClient> | null = null;

function setConnected(v: boolean) {
  connectedRef = v;
  connectedListeners.forEach((fn) => fn(v));
}

function handleEvent(event: MessageEvent) {
  try {
    const msg = JSON.parse(event.data);
    globalQueryClient?.invalidateQueries({ queryKey: ['tasks'] });
    globalQueryClient?.invalidateQueries({ queryKey: ['task-badge'] });
    if (msg.projectId) {
      globalQueryClient?.invalidateQueries({ queryKey: ['project', Number(msg.projectId)] });
      globalQueryClient?.invalidateQueries({ queryKey: ['pages'] });
    }
  } catch {
    // 心跳等非 JSON 消息
  }
}

function connect(token: string) {
  if (sharedSource) return;
  const source = new EventSource(`/api/tasks/events?token=${encodeURIComponent(token)}`);
  sharedSource = source;
  source.onopen = () => setConnected(true);
  source.onerror = () => setConnected(false);
  source.addEventListener('task.created', handleEvent);
  source.addEventListener('task.progress', handleEvent);
  source.addEventListener('task.status', handleEvent);
  source.addEventListener('page.generated', handleEvent);
}

function disconnect() {
  if (sharedSource) {
    sharedSource.close();
    sharedSource = null;
  }
  setConnected(false);
}

/**
 * 任务事件 SSE 订阅(模块级单例,全应用只维持一条连接):
 * - 实时接收任务事件并失效 TanStack Query 缓存;
 * - EventSource 断线自动重连;返回连接状态(任务页据此降级轮询)。
 */
export function useSseTasks(enabled: boolean): boolean {
  const token = useAuthStore((s) => s.token);
  const queryClient = useQueryClient();
  const [connected, setLocalConnected] = useState(connectedRef);

  // 首个调用者注册全局 queryClient 引用
  useEffect(() => {
    globalQueryClient = queryClient;
  }, [queryClient]);

  // 连接状态同步到组件本地
  useEffect(() => {
    const listener = (v: boolean) => setLocalConnected(v);
    connectedListeners.add(listener);
    return () => { connectedListeners.delete(listener); };
  }, []);

  // 建立/断开连接(引用计数)
  useEffect(() => {
    if (!enabled || !token) return;
    refCount++;
    connect(token);
    return () => {
      refCount--;
      if (refCount <= 0) {
        disconnect();
      }
    };
  }, [enabled, token]);

  return connected;
}

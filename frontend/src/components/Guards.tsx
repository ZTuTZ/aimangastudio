import type { ReactElement } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';

/** 未登录 → /login */
export function RequireAuth({ children }: { children: ReactElement }) {
  const token = useAuthStore((s) => s.token);
  return token ? children : <Navigate to="/login" replace />;
}

/** 已登录访问 /login → 回作品库 */
export function RedirectIfAuthed({ children }: { children: ReactElement }) {
  const token = useAuthStore((s) => s.token);
  return token ? <Navigate to="/" replace /> : children;
}

/** 非管理员 → /403 */
export function RequireAdmin({ children }: { children: ReactElement }) {
  const role = useAuthStore((s) => s.user?.role);
  return role === 'ADMIN' ? children : <Navigate to="/403" replace />;
}

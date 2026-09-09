import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface CurrentUser {
  id: number;
  username: string;
  role: 'ADMIN' | 'USER';
}

interface AuthState {
  token: string | null;
  refreshToken: string | null;
  user: CurrentUser | null;
  setAuth: (token: string, refreshToken: string, user: CurrentUser) => void;
  setUser: (user: CurrentUser) => void;
  clear: () => void;
}

/** 认证状态(localStorage 持久化;http.ts 依赖本 store 注入/刷新 token) */
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      refreshToken: null,
      user: null,
      setAuth: (token, refreshToken, user) => set({ token, refreshToken, user }),
      setUser: (user) => set({ user }),
      clear: () => set({ token: null, refreshToken: null, user: null }),
    }),
    { name: 'aimanga-v2-auth' },
  ),
);

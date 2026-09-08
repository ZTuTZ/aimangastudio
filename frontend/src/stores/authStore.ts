import { create } from 'zustand';

export interface CurrentUser {
  id: number;
  username: string;
  role: 'ADMIN' | 'USER';
}

interface AuthState {
  token: string | null;
  user: CurrentUser | null;
  setAuth: (token: string, user: CurrentUser) => void;
  clear: () => void;
}

/** 认证状态(T2.1 接入真实登录;持久化到 localStorage 一并在此任务完成) */
export const useAuthStore = create<AuthState>()((set) => ({
  token: null,
  user: null,
  setAuth: (token, user) => set({ token, user }),
  clear: () => set({ token: null, user: null }),
}));

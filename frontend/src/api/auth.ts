import { http, unwrap } from './http';
import type { CurrentUser } from '@/stores/authStore';

export interface LoginResult {
  accessToken: string;
  refreshToken: string;
  user: CurrentUser;
}

export const authApi = {
  login: (username: string, password: string) =>
    unwrap<LoginResult>(http.post('/auth/login', { username, password })),

  /** 仅在 401 自动刷新中使用,业务代码不要直接调用 */
  refresh: (refreshToken: string) => unwrap<LoginResult>(http.post('/auth/refresh', { refreshToken })),

  me: () => unwrap<CurrentUser>(http.get('/auth/me')),

  changePassword: (oldPassword: string, newPassword: string) =>
    unwrap<void>(http.post('/auth/password', { oldPassword, newPassword })),

  logout: (refreshToken: string | null) =>
    http.post('/auth/logout', { refreshToken }).catch(() => undefined),
};

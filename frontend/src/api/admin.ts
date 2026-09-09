import { http, unwrap } from './http';

export interface UserRow {
  id: number;
  username: string;
  role: 'ADMIN' | 'USER';
  status: 0 | 1;
  createTime: string;
  projectCount: number;
}

export const adminApi = {
  listUsers: () => unwrap<UserRow[]>(http.get('/admin/users')),

  createUser: (data: { username: string; password: string; role: 'ADMIN' | 'USER' }) =>
    unwrap<UserRow>(http.post('/admin/users', data)),

  updateUser: (id: number, data: { status?: 0 | 1; newPassword?: string }) =>
    unwrap<void>(http.put(`/admin/users/${id}`, data)),

  deleteUser: (id: number, confirmUsername: string) =>
    unwrap<void>(http.delete(`/admin/users/${id}`, { data: { confirmUsername } })),
};

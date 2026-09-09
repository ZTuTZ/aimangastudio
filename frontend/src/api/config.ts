import { http, unwrap } from './http';

export type ConfigMap = Record<string, string>;

export const configApi = {
  list: () => unwrap<ConfigMap>(http.get('/admin/configs')),
  save: (configs: ConfigMap) => unwrap<ConfigMap>(http.put('/admin/configs', { configs })),
  verify: (kind: 'text' | 'image') =>
    unwrap<{ ok: boolean; message: string; url?: string }>(http.post('/admin/configs/verify', { kind })),
};

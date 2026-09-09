import axios, { AxiosError, type AxiosResponse } from 'axios';
import { useAuthStore, type CurrentUser } from '@/stores/authStore';

/** 统一响应体(与后端 Result 对齐) */
export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
});

http.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/** 并发 401 时共享同一次刷新 */
let refreshing: Promise<string | null> | null = null;

async function doRefresh(): Promise<string | null> {
  const { refreshToken, setAuth, clear } = useAuthStore.getState();
  if (!refreshToken) return null;
  try {
    const { data } = await axios.post<ApiResult<{ accessToken: string; refreshToken: string; user: CurrentUser }>>(
      '/api/auth/refresh',
      { refreshToken },
    );
    if (data.code !== 0) {
      clear();
      return null;
    }
    setAuth(data.data.accessToken, data.data.refreshToken, data.data.user);
    return data.data.accessToken;
  } catch {
    clear();
    return null;
  }
}

http.interceptors.response.use(undefined, async (error: AxiosError<ApiResult<unknown>>) => {
  const config = error.config as (NonNullable<AxiosError['config']> & { _retried?: boolean }) | undefined;
  const isAuthEndpoint = typeof config?.url === 'string' && config.url.includes('/auth/');
  if (error.response?.status === 401 && config && !config._retried && !isAuthEndpoint) {
    config._retried = true;
    refreshing ||= doRefresh().finally(() => {
      refreshing = null;
    });
    const token = await refreshing;
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
      return http(config);
    }
    if (location.pathname !== '/login') {
      location.href = '/login';
    }
  }
  return Promise.reject(error);
});

/** 解包统一响应:code!=0 抛业务错误,成功返回 data */
export async function unwrap<T>(promise: Promise<AxiosResponse<ApiResult<T>>>): Promise<T> {
  try {
    const { data } = await promise;
    if (data.code !== 0) {
      throw new Error(data.message || '请求失败');
    }
    return data.data;
  } catch (e) {
    // 把后端 {code,message} 体里的 message 转为业务错误,避免 UI 显示
    // "Request failed with status code xxx" 这类原始报错
    if (axios.isAxiosError(e)) {
      const body = e.response?.data as Partial<ApiResult<unknown>> | undefined;
      const friendly = body?.message;
      if (friendly) {
        throw new Error(friendly);
      }
      throw new Error(`请求失败(${e.response?.status ?? '网络异常'})`);
    }
    throw e;
  }
}

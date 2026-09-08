import axios, { type AxiosResponse } from 'axios';
import { useAuthStore } from '@/stores/authStore';

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

http.interceptors.response.use(
  (resp) => resp,
  (error) => {
    // T2.1:401 时尝试刷新 token 并重放,失败跳转 /login
    return Promise.reject(error);
  },
);

/** 解包统一响应:code!=0 抛业务错误,成功返回 data */
export async function unwrap<T>(promise: Promise<AxiosResponse<ApiResult<T>>>): Promise<T> {
  const { data } = await promise;
  if (data.code !== 0) {
    throw new Error(data.message || '请求失败');
  }
  return data.data;
}

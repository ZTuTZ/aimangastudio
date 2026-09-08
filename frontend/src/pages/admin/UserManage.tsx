import { ComingSoon } from '@/components/ComingSoon';

export function UserManage() {
  return (
    <ComingSoon
      title="账号管理"
      items={['用户列表（用户名 / 角色 / 状态 / 创建时间）', '创建账号（用户名 + 初始密码 + 角色）', '停用 / 启用 / 重置密码 / 删除（高危确认）']}
    />
  );
}

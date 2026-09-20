export const ACTIVE_TASK_STATUSES = [0, 1, 6] as const;

export function isTaskActive(status: number): boolean {
  return ACTIVE_TASK_STATUSES.includes(status as (typeof ACTIVE_TASK_STATUSES)[number]);
}

export function isTaskSlotOccupied(status: number): boolean {
  return isTaskActive(status) || status === 7;
}

export function canResumeTask(status: number): boolean {
  return status === 7;
}

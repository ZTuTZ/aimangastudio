import { describe, expect, it } from 'vitest';
import { canResumeTask, isTaskActive, isTaskSlotOccupied } from './taskState';

describe('task state contract', () => {
  it('keeps a paused task in the unique task slot without treating it as running', () => {
    expect(isTaskActive(7)).toBe(false);
    expect(isTaskSlotOccupied(7)).toBe(true);
    expect(canResumeTask(7)).toBe(true);
  });

  it('allows only active and paused states to occupy the task slot', () => {
    expect([0, 1, 6, 7].filter(isTaskSlotOccupied)).toEqual([0, 1, 6, 7]);
    expect([2, 3, 4, 5].some(isTaskSlotOccupied)).toBe(false);
  });
});

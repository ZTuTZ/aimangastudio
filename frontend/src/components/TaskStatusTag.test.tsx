import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TaskStatusTag } from './TaskStatusTag';

describe('TaskStatusTag', () => {
  it('shows drain intent while a running task is pausing', () => {
    render(<TaskStatusTag task={{ status: 1, pauseRequested: true }} />);
    expect(screen.getByText('暂停中')).toBeInTheDocument();
  });

  it('shows the durable paused state', () => {
    render(<TaskStatusTag task={{ status: 7, pauseRequested: false }} />);
    expect(screen.getByText('已暂停')).toBeInTheDocument();
  });
});

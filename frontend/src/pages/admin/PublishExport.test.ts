import { describe, expect, it } from 'vitest';
import { parseExportResult } from './PublishExport';

describe('parseExportResult', () => {
  it('keeps the downloadable artifact and per-project errors', () => {
    const parsed = parseExportResult(JSON.stringify({
      artifactId: 91,
      downloadUrl: '/api/admin/export/artifacts/91/download',
      success: 1,
      failed: 1,
      items: [
        { projectId: 1, exported: true },
        { projectId: 2, exported: false, error: '文本层已过期' },
      ],
    }));
    expect(parsed?.artifactId).toBe(91);
    expect(parsed?.downloadUrl).toContain('/artifacts/91/download');
    expect(parsed?.items?.[1].error).toBe('文本层已过期');
  });

  it('rejects malformed task result JSON', () => {
    expect(parseExportResult('{broken')).toBeNull();
  });
});

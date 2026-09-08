import { Empty, Typography } from 'antd';

interface ComingSoonProps {
  title: string;
  items?: string[];
}

/** Phase 1 占位页:展示该页面的规划内容,Tx.y 任务实现后替换 */
export function ComingSoon({ title, items }: ComingSoonProps) {
  return (
    <div className="bg-white rounded-xl border border-[#eef0f4] p-10">
      <Typography.Title level={4}>{title}</Typography.Title>
      <Typography.Paragraph type="secondary">该页面将在后续开发任务中实现。</Typography.Paragraph>
      {items && items.length > 0 && (
        <div className="max-w-xl">
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <ul className="list-disc text-left text-gray-500 mx-6 mt-2 leading-7">
                {items.map((it) => (
                  <li key={it}>{it}</li>
                ))}
              </ul>
            }
          />
        </div>
      )}
    </div>
  );
}

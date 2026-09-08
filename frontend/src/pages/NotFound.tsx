import { Button, Result } from 'antd';
import { useNavigate } from 'react-router-dom';

interface NotFoundProps {
  title?: string;
  subTitle?: string;
}

export function NotFound({ title = '404', subTitle = '页面不存在' }: NotFoundProps) {
  const navigate = useNavigate();
  return (
    <Result
      status="404"
      title={title}
      subTitle={subTitle}
      extra={
        <Button type="primary" onClick={() => navigate('/')}>
          返回作品库
        </Button>
      }
    />
  );
}

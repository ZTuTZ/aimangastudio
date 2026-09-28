import { Button } from 'antd';
import { useNavigate } from 'react-router-dom';

interface NotFoundProps {
  title?: string;
  subTitle?: string;
}

export function NotFound({ title = '404', subTitle = '页面不存在' }: NotFoundProps) {
  const navigate = useNavigate();
  return (
    <div className="studio-empty" role="main">
      <span className="studio-page-kicker">AIMANGA STUDIO</span>
      <div className="studio-empty-number">{title}</div>
      <h1>{subTitle}</h1>
      <p>故事还在继续，回到作品库接着创作。</p>
      <Button type="primary" onClick={() => navigate('/')}>返回作品库</Button>
    </div>
  );
}

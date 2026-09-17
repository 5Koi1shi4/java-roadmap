import type { StatusView } from '../api/client';

type StatusCardProps = {
  status: StatusView;
};

function formatDate(value: string | null | undefined): string {
  if (!value) {
    return '未提供';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(date);
}

function typeLabel(type: string): string {
  if (type === 'orders') {
    return '订单';
  }
  if (type === 'disputes') {
    return '争议案件';
  }
  if (type === 'warranties') {
    return '质保案件';
  }
  return type;
}

export function StatusCard({ status }: StatusCardProps) {
  return (
    <section className="status-card" aria-labelledby="status-heading">
      <h2 id="status-heading">当前状态</h2>
      <div className="status-card__identity">
        <span className="status-card__type">{typeLabel(status.type)}</span>
        <p className="status-card__code">{status.status}</p>
      </div>
      <ol className="status-timeline" aria-label="办理状态时间线">
        <li>
          <span className="status-timeline__dot" aria-hidden="true" />
          <div>
            <span className="status-timeline__label">已建立记录</span>
            <span className="status-timeline__value">{formatDate(status.createdAt)}</span>
          </div>
        </li>
        <li aria-current="step">
          <span className="status-timeline__dot" aria-hidden="true" />
          <div>
            <span className="status-timeline__label">当前状态</span>
            <span className="status-timeline__value">状态已由服务端确认</span>
          </div>
        </li>
        {status.deadline ? (
          <li>
            <span className="status-timeline__dot" aria-hidden="true" />
            <div>
              <span className="status-timeline__label">办理截止</span>
              <span className="status-timeline__value">{formatDate(status.deadline)}</span>
            </div>
          </li>
        ) : null}
      </ol>
    </section>
  );
}

import { useEffect, useState } from 'react';
import { ApiError, listResources, type ResourceType, type StatusView } from '../api/client';

export type SelectedResource = {
  kind: 'order' | 'dispute' | 'warranty';
  id: string;
  label: string;
};

type ResourcePickerProps = {
  token: string;
  onSelect: (resource: SelectedResource | null) => void;
  onUnauthorized?: () => void;
};

const RESOURCE_TYPES: ResourceType[] = ['orders', 'disputes', 'warranties'];
const TYPE_LABELS: Record<ResourceType, string> = {
  orders: '订单',
  disputes: '争议案件',
  warranties: '质保案件'
};
const RESOURCE_KINDS: Record<ResourceType, SelectedResource['kind']> = {
  orders: 'order',
  disputes: 'dispute',
  warranties: 'warranty'
};

function resourceName(type: ResourceType, item: StatusView): string {
  return `${TYPE_LABELS[type]} · ${item.status}`;
}

export function ResourcePicker({ token, onSelect, onUnauthorized }: ResourcePickerProps) {
  const [items, setItems] = useState<Record<ResourceType, StatusView[]>>({
    orders: [],
    disputes: [],
    warranties: []
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    setSelectedId(null);
    onSelect(null);
    void Promise.all(RESOURCE_TYPES.map(async (type) => [type, await listResources(type, token)] as const))
      .then((pages) => {
        if (!active) {
          return;
        }
        const next = { orders: [], disputes: [], warranties: [] } as Record<ResourceType, StatusView[]>;
        pages.forEach(([type, page]) => {
          next[type] = page.items;
        });
        setItems(next);
      })
      .catch((reason: unknown) => {
        if (!active) {
          return;
        }
        const apiError = reason instanceof ApiError ? reason : null;
        setError(apiError?.userMessage ?? '服务暂不可用');
        if (apiError?.status === 401) {
          onUnauthorized?.();
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [onSelect, onUnauthorized, reloadKey, token]);

  function choose(type: ResourceType, item: StatusView) {
    setSelectedId(item.id);
    onSelect({ kind: RESOURCE_KINDS[type], id: item.id, label: TYPE_LABELS[type] });
  }

  return (
    <section className="resource-panel" aria-labelledby="resource-picker-heading">
      <div className="resource-panel__header">
        <div>
          <h2 className="panel-heading" id="resource-picker-heading">选择本人资源</h2>
          <p>选择后，问题会带上明确的资源 ID。</p>
        </div>
        <button className="quiet-button" type="button" onClick={() => setReloadKey((key) => key + 1)} disabled={loading}>
          刷新
        </button>
      </div>
      {loading ? <p className="resource-loading" role="status">正在读取本人资源…</p> : null}
      {error ? (
        <div className="resource-error" role="alert">
          <p>{error}</p>
          <button className="quiet-button retry-button" type="button" onClick={() => setReloadKey((key) => key + 1)}>
            重试
          </button>
        </div>
      ) : null}
      {!loading && !error ? (
        <div className="resource-groups">
          {RESOURCE_TYPES.map((type) => (
            <section className="resource-group" key={type} aria-labelledby={`${type}-heading`}>
              <h3 id={`${type}-heading`}>{TYPE_LABELS[type]}</h3>
              {items[type].length ? (
                <ul className="resource-list">
                  {items[type].map((item) => (
                    <li key={item.id}>
                      <button
                        className="resource-button"
                        type="button"
                        aria-pressed={selectedId === item.id}
                        onClick={() => choose(type, item)}
                      >
                        <span className="resource-button__main">{resourceName(type, item)}</span>
                        <span className="resource-button__meta">建立于 {item.createdAt}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              ) : <p className="resource-empty">暂无本人{TYPE_LABELS[type]}。</p>}
            </section>
          ))}
        </div>
      ) : null}
    </section>
  );
}

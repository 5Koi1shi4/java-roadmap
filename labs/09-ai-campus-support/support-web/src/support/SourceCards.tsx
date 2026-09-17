import type { SourceView } from '../api/client';

export function SourceCards({ sources }: { sources: SourceView[] }) {
  return (
    <section className="source-cards" aria-labelledby="sources-heading">
      <h2 id="sources-heading">规则来源</h2>
      {sources.length ? (
        <ul className="source-list">
          {sources.map((source) => (
            <li key={`${source.sourceId}-${source.version}`}>
              <article>
                <h3>{source.title}</h3>
                <p>{source.sourceId} · 版本 {source.version}</p>
              </article>
            </li>
          ))}
        </ul>
      ) : (
        <p className="resource-empty">暂未找到匹配的规则来源。</p>
      )}
    </section>
  );
}

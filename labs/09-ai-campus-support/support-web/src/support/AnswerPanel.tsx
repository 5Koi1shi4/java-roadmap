import type { AnswerResponse } from '../api/client';
import { SourceCards } from './SourceCards';
import { StatusCard } from './StatusCard';

type AnswerPanelProps = {
  result: AnswerResponse | null;
  includeDetails?: boolean;
};

export function AnswerPanel({ result, includeDetails = true }: AnswerPanelProps) {
  if (!result) {
    return (
      <section className="answer-panel__empty" aria-labelledby="answer-empty-heading">
        <h2 id="answer-empty-heading">答复会显示在这里</h2>
        <p>提交一个规则问题后，可在此查看答复与依据。</p>
      </section>
    );
  }

  return (
    <section className="answer-panel" aria-live="polite" aria-labelledby="answer-heading">
      <div className="answer-panel__body">
        <h2 id="answer-heading">规则答复</h2>
        <p className="answer-copy">{result.answer}</p>
        {includeDetails && result.status ? <StatusCard status={result.status} /> : null}
        {includeDetails ? <SourceCards sources={result.sources} /> : null}
      </div>
    </section>
  );
}

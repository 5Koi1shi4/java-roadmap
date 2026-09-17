import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, askSupport, type AnswerResponse } from '../api/client';
import { LoginForm } from '../auth/LoginForm';
import { RegisterForm } from '../auth/RegisterForm';
import { useAuth } from '../auth/AuthProvider';
import { AnswerPanel } from './AnswerPanel';
import { ResourcePicker, type SelectedResource } from './ResourcePicker';
import { SourceCards } from './SourceCards';
import { StatusCard } from './StatusCard';

type AuthMode = 'login' | 'register';

export function SupportDesk() {
  const { accessToken, logout } = useAuth();
  const [authMode, setAuthMode] = useState<AuthMode>('login');
  const [question, setQuestion] = useState('');
  const [selectedResource, setSelectedResource] = useState<SelectedResource | null>(null);
  const [result, setResult] = useState<AnswerResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [reauthRequired, setReauthRequired] = useState(false);
  const requestVersion = useRef(0);
  const activeToken = useRef(accessToken);
  const previousToken = useRef(accessToken);
  const preserveUnauthorizedMessage = useRef(false);
  activeToken.current = accessToken;

  useEffect(() => {
    if (previousToken.current === accessToken) {
      return;
    }
    previousToken.current = accessToken;
    requestVersion.current += 1;
    setQuestion('');
    setSelectedResource(null);
    setResult(null);
    setLoading(false);
    if (!(preserveUnauthorizedMessage.current && accessToken === null)) {
      setError(null);
      setReauthRequired(false);
    }
  }, [accessToken]);

  const handleUnauthorized = useCallback(() => {
    requestVersion.current += 1;
    preserveUnauthorizedMessage.current = true;
    logout();
    setQuestion('');
    setSelectedResource(null);
    setResult(null);
    setError('登录已失效，请重新登录。');
    setLoading(false);
    setReauthRequired(true);
  }, [logout]);

  const handleLogout = useCallback(() => {
    requestVersion.current += 1;
    preserveUnauthorizedMessage.current = false;
    logout();
    setQuestion('');
    setSelectedResource(null);
    setResult(null);
    setError(null);
    setLoading(false);
    setReauthRequired(false);
  }, [logout]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const cleanedQuestion = question.trim();
    if (!cleanedQuestion) {
      setError('请先填写问题。');
      return;
    }
    const request = selectedResource?.kind === 'order'
      ? { question: cleanedQuestion, orderId: selectedResource.id }
      : selectedResource?.kind === 'dispute'
        ? { question: cleanedQuestion, caseId: selectedResource.id, caseType: 'DISPUTE' as const }
        : selectedResource?.kind === 'warranty'
          ? { question: cleanedQuestion, caseId: selectedResource.id, caseType: 'WARRANTY' as const }
          : { question: cleanedQuestion };
    const currentRequestVersion = ++requestVersion.current;
    const requestToken = accessToken;
    setLoading(true);
    setError(null);
    setResult(null);
    setReauthRequired(false);
    try {
      const answer = await askSupport(request, accessToken);
      if (currentRequestVersion !== requestVersion.current || requestToken !== activeToken.current) {
        return;
      }
      setResult(answer);
    } catch (reason: unknown) {
      if (currentRequestVersion !== requestVersion.current || requestToken !== activeToken.current) {
        return;
      }
      const apiError = reason instanceof ApiError ? reason : null;
      if (apiError?.status === 401) {
        handleUnauthorized();
      } else {
        setError(apiError?.userMessage ?? '服务暂不可用');
      }
    } finally {
      if (currentRequestVersion === requestVersion.current && requestToken === activeToken.current) {
        setLoading(false);
      }
    }
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand-lockup">
          <span className="brand-seal" aria-hidden="true">校务</span>
          <div>
            <p className="brand-name">校园服务台</p>
            <p className="brand-caption">规则与进度，一处查清</p>
          </div>
        </div>
        <div className="header-actions">
          {accessToken ? (
            <>
              <span className="session-label">已登录，可查询本人资源</span>
              <button className="quiet-button" type="button" onClick={() => {
                handleLogout();
              }}>
                退出登录
              </button>
            </>
          ) : <span className="session-label">公开规则随时可查</span>}
        </div>
      </header>

      <main className="desk-grid">
        <section className="main-column" aria-labelledby="page-title">
          <p className="eyebrow">校内交易支持 / 规则先行</p>
          <h1 className="page-title" id="page-title">把问题交给服务台，把依据留在手边。</h1>
          <p className="intro-copy">
            先回答公开规则；登录后，再把问题关联到你的订单、争议或质保案件。确定性状态会单独列出，不会被模型解释替代。
          </p>
          <hr className="desk-rule" />

          <section className="panel question-panel" aria-labelledby="question-heading">
            <h2 className="panel-heading" id="question-heading">问一个问题</h2>
            <p className="panel-lead">描述你想确认的规则，或补充已选择资源的具体情况。</p>
            <form onSubmit={submit}>
              <div className="field">
                <label htmlFor="support-question">问题</label>
                <textarea
                  id="support-question"
                  name="question"
                  value={question}
                  onChange={(event) => setQuestion(event.target.value)}
                  placeholder="例如：订单进入售后窗口后，什么时候可以申请退款？"
                  maxLength={2000}
                  required
                />
              </div>
              {selectedResource ? <p className="form-status">已关联：{selectedResource.label}（仅发送资源 ID）</p> : null}
              {error ? (
                <div className="desk-error" role="alert">
                  <p>{error}</p>
                  {loading || reauthRequired ? null : <button className="quiet-button retry-button" type="submit">重试</button>}
                </div>
              ) : null}
              <div className="form-actions">
                <button className="primary-button" type="submit" disabled={loading}>
                  {loading ? '正在查询…' : '获取规则答复'}
                </button>
                {selectedResource ? (
                  <button className="quiet-button" type="button" onClick={() => setSelectedResource(null)}>
                    清除资源关联
                  </button>
                ) : null}
              </div>
            </form>
          </section>

          <AnswerPanel result={result} includeDetails={false} />
        </section>

        <aside className="side-column" aria-label="服务台侧栏">
          {accessToken ? (
            <ResourcePicker
              token={accessToken}
              onSelect={setSelectedResource}
              onUnauthorized={handleUnauthorized}
            />
          ) : (
            <section className="auth-panel" aria-labelledby="auth-heading">
              <div className="auth-panel__header">
                <div>
                  <h2 className="panel-heading" id="auth-heading">
                    {authMode === 'login' ? '登录后查询本人进度' : '创建服务台账号'}
                  </h2>
                  <p>{authMode === 'login' ? '账号只保存在当前会话内。' : '使用校园邮箱完成一次验证码验证。'}</p>
                </div>
              </div>
              {authMode === 'login' ? (
                <LoginForm
                  onSwitchRegister={() => setAuthMode('register')}
                  onSuccess={() => {
                    setError(null);
                    setReauthRequired(false);
                  }}
                />
              ) : (
                <RegisterForm
                  onSwitchLogin={() => setAuthMode('login')}
                  onRegistered={() => {
                    setError(null);
                    setReauthRequired(false);
                  }}
                />
              )}
            </section>
          )}
          {result ? (
            <section className="side-result panel" aria-label="答复依据">
              {result.status ? <StatusCard status={result.status} /> : null}
              <SourceCards sources={result.sources} />
            </section>
          ) : null}
        </aside>
      </main>
    </div>
  );
}

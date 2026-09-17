import { useState, type FormEvent } from 'react';
import { ApiError, loginAccount } from '../api/client';
import { useAuth } from './AuthProvider';

type LoginFormProps = {
  onSwitchRegister?: () => void;
  onSuccess?: () => void;
};

export function LoginForm({ onSwitchRegister, onSuccess }: LoginFormProps) {
  const auth = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!email.trim() || !password) {
      setError('请输入校园邮箱和密码。');
      return;
    }
    setError(null);
    setBusy(true);
    try {
      const result = await loginAccount({ email: email.trim(), password });
      auth.login(result.accessToken);
      onSuccess?.();
    } catch (reason: unknown) {
      setError(reason instanceof ApiError ? reason.userMessage : '服务暂不可用');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="field">
        <label htmlFor="login-email">校园邮箱</label>
        <input
          id="login-email"
          name="email"
          type="email"
          autoComplete="username"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="login-password">密码</label>
        <input
          id="login-password"
          name="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          required
        />
      </div>
      {error ? <p className="form-error" role="alert">{error}</p> : null}
      <div className="form-actions">
        <button className="primary-button" type="submit" disabled={busy}>
          {busy ? '登录中…' : '登录'}
        </button>
        {onSwitchRegister ? (
          <button className="quiet-button" type="button" onClick={onSwitchRegister}>
            创建新账号
          </button>
        ) : null}
      </div>
    </form>
  );
}

import { useRef, useState, type FormEvent } from 'react';
import { ApiError, issueVerification, loginAccount, registerAccount } from '../api/client';
import { useAuth } from './AuthProvider';

type RegisterFormProps = {
  onSwitchLogin?: () => void;
  onRegistered?: () => void;
};

export function RegisterForm({ onSwitchLogin, onRegistered }: RegisterFormProps) {
  const auth = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [verificationSent, setVerificationSent] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const emailRef = useRef(email);

  async function sendCode() {
    if (!email.trim()) {
      setError('请先填写校园邮箱。');
      return;
    }
    setError(null);
    setStatus(null);
    setBusy(true);
    const requestedEmail = email.trim();
    try {
      await issueVerification({ email: requestedEmail, purpose: 'REGISTER' });
      if (emailRef.current.trim() !== requestedEmail) {
        return;
      }
      setVerificationSent(true);
      setStatus('验证码已发送，请查收邮箱。');
    } catch (reason: unknown) {
      setError(reason instanceof ApiError ? reason.userMessage : '服务暂不可用');
    } finally {
      setBusy(false);
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!email.trim() || password.length < 8 || !/^\d{6}$/.test(code)) {
      setError('请填写邮箱、至少 8 位密码和六位数字验证码。');
      return;
    }
    setError(null);
    setStatus(null);
    setBusy(true);
    try {
      await registerAccount({ email: email.trim(), password, code });
      const result = await loginAccount({ email: email.trim(), password });
      auth.login(result.accessToken);
      setStatus('注册完成，已登录服务台。');
      onRegistered?.();
    } catch (reason: unknown) {
      setError(reason instanceof ApiError ? reason.userMessage : '服务暂不可用');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="field">
        <label htmlFor="register-email">校园邮箱</label>
        <input
          id="register-email"
          name="email"
          type="email"
          autoComplete="username"
          value={email}
          onChange={(event) => {
            const nextEmail = event.target.value;
            emailRef.current = nextEmail;
            if (verificationSent && nextEmail !== email) {
              setVerificationSent(false);
              setCode('');
              setStatus(null);
            }
            setEmail(nextEmail);
          }}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="register-password">设置密码</label>
        <input
          id="register-password"
          name="password"
          type="password"
          autoComplete="new-password"
          minLength={8}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="register-code">注册验证码</label>
        <input
          id="register-code"
          name="code"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          value={code}
          onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))}
          required
        />
      </div>
      {status ? <p className="form-status" role="status">{status}</p> : null}
      {error ? <p className="form-error" role="alert">{error}</p> : null}
      <div className="form-actions">
        <button className="secondary-button" type="button" onClick={() => void sendCode()} disabled={busy}>
          {busy && !verificationSent ? '发送中…' : '发送验证码'}
        </button>
        <button className="primary-button" type="submit" disabled={busy || !verificationSent}>
          {busy && verificationSent ? '注册中…' : '完成注册'}
        </button>
        {onSwitchLogin ? (
          <button className="quiet-button" type="button" onClick={onSwitchLogin}>
            返回登录
          </button>
        ) : null}
      </div>
    </form>
  );
}

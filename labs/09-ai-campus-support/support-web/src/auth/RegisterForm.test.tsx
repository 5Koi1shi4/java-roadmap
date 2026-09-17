import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { AuthProvider } from './AuthProvider';
import { RegisterForm } from './RegisterForm';

describe('注册流程', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  test('依次发送验证码、注册并登录', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input);
      if (path.endsWith('/email-verifications')) {
        return new Response(JSON.stringify({ status: 'sent', expiresIn: 600 }), { status: 200 });
      }
      if (path.endsWith('/register')) {
        return new Response(JSON.stringify({ status: 'registered' }), { status: 201 });
      }
      return new Response(JSON.stringify({
        accessToken: 'short-test-token', tokenType: 'Bearer', expiresIn: 900,
        userId: 'user-1', roles: ['USER']
      }), { status: 200 });
    });

    render(<AuthProvider><RegisterForm /></AuthProvider>);
    fireEvent.change(screen.getByRole('textbox', { name: '校园邮箱' }), {
      target: { value: 'buyer@stu.example.edu.cn' }
    });
    fireEvent.click(screen.getByRole('button', { name: '发送验证码' }));
    await waitFor(() => expect(screen.getByText('验证码已发送，请查收邮箱。')).toBeVisible());

    fireEvent.change(screen.getByRole('textbox', { name: '注册验证码' }), {
      target: { value: '123456' }
    });
    fireEvent.change(screen.getByLabelText('设置密码'), {
      target: { value: 'strong-pass-1' }
    });
    fireEvent.click(screen.getByRole('button', { name: '完成注册' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    expect(fetchMock.mock.calls.map(([input]) => String(input))).toEqual([
      '/api/auth/email-verifications',
      '/api/auth/register',
      '/api/auth/login'
    ]);
  });

  test('验证码发送后修改邮箱会清除验证码上下文', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ status: 'sent', expiresIn: 600 }), { status: 200 })
    );

    render(<AuthProvider><RegisterForm /></AuthProvider>);
    const email = screen.getByRole('textbox', { name: '校园邮箱' });
    fireEvent.change(email, { target: { value: 'first@stu.example.edu.cn' } });
    fireEvent.click(screen.getByRole('button', { name: '发送验证码' }));
    await waitFor(() => expect(screen.getByText('验证码已发送，请查收邮箱。')).toBeVisible());

    const code = screen.getByRole('textbox', { name: '注册验证码' });
    fireEvent.change(code, { target: { value: '123456' } });
    fireEvent.change(email, { target: { value: 'second@stu.example.edu.cn' } });

    expect(code).toHaveValue('');
    expect(screen.getByRole('button', { name: '完成注册' })).toBeDisabled();
    expect(screen.queryByText('验证码已发送，请查收邮箱。')).not.toBeInTheDocument();
  });
});

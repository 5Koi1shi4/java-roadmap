import { describe, expect, test } from 'vitest';
import { createAuthState } from './AuthProvider';

describe('身份状态', () => {
  test('登出后不再发送 Bearer，页面刷新不恢复 Token', () => {
    const auth = createAuthState();

    auth.login('short-test-token');
    expect(auth.authorization()).toBe('Bearer short-test-token');

    auth.logout();
    expect(auth.authorization()).toBeNull();
    expect(localStorage.length).toBe(0);
  });
});

import { beforeEach, describe, expect, test, vi } from 'vitest';
import { askSupport, listResources, mapStatus, type AnswerRequest } from './client';

describe('支持服务客户端', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  test('401 与 403 提供不同且可行动的中文提示', () => {
    expect(mapStatus(401).userMessage).toBe('登录已失效，请重新登录。');
    expect(mapStatus(403).userMessage).toBe('无权访问该资源，请确认账号权限。');
  });

  test('询问私人资源时发送显式 ID 和 Bearer，并禁用缓存', async () => {
    const request: AnswerRequest = {
      question: '订单什么时候可以确认收货？',
      orderId: 'order-1'
    };
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ answer: '请在状态截止前确认。', sources: [] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await askSupport(request, 'short-test-token');

    expect(fetchMock).toHaveBeenCalledWith('/api/ai/support/answers', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json; charset=UTF-8',
        Authorization: 'Bearer short-test-token'
      },
      body: JSON.stringify(request),
      cache: 'no-store'
    });
  });

  test('本人资源列表使用资源类型路径和 Bearer', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ items: [], nextCursor: null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await listResources('orders', 'short-test-token');

    expect(fetchMock).toHaveBeenCalledWith('/api/support/orders', {
      method: 'GET',
      headers: { Authorization: 'Bearer short-test-token' },
      cache: 'no-store'
    });
  });
});

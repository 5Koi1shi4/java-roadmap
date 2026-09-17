import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useEffect } from 'react';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { AuthProvider, useAuth } from '../auth/AuthProvider';
import type { AnswerResponse, ResourcePage } from '../api/client';
import { AnswerPanel } from './AnswerPanel';
import { ResourcePicker } from './ResourcePicker';
import { SupportDesk } from './SupportDesk';

describe('校园客服工作台', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  function SignedInDesk() {
    const auth = useAuth();
    useEffect(() => {
      auth.login('session-token');
      // The fixture seeds one session; it must not log back in after the test logs out.
    }, []);
    return <SupportDesk />;
  }

  function resourceResponse(type: 'orders' | 'disputes' | 'warranties') {
    const items = type === 'orders'
      ? [{ id: 'order-1', type: 'orders', status: 'PAID', createdAt: '2026-09-17T08:00:00Z' }]
      : [];
    return new Response(JSON.stringify({ items, nextCursor: null }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  test('规则解释不能覆盖确定性状态', () => {
    const result = {
      answer: '退款条件以规则为准',
      status: { type: 'ORDER', status: 'PAID' },
      sources: [{ sourceId: 'refund-v1', title: '退款规则', version: 'v1' }]
    } as AnswerResponse;

    render(<AnswerPanel result={result} />);

    expect(screen.getByRole('heading', { name: '当前状态' })).toBeVisible();
    expect(screen.getByText('PAID')).toBeVisible();
    expect(screen.getByRole('heading', { name: '规则来源' })).toBeVisible();
  });

  test('未登录用户可以提交公开规则问题', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({
        answer: '请以公开规则为准。',
        sources: [{ sourceId: 'refund-v1', title: '退款规则', version: 'v1' }]
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    );

    render(<AuthProvider><SupportDesk /></AuthProvider>);
    fireEvent.change(screen.getByRole('textbox', { name: '问题' }), {
      target: { value: '退款条件是什么？' }
    });
    fireEvent.click(screen.getByRole('button', { name: '获取规则答复' }));

    await waitFor(() => expect(screen.getByText('请以公开规则为准。')).toBeVisible());
    expect(fetchMock).toHaveBeenCalledWith('/api/ai/support/answers', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ question: '退款条件是什么？' })
    }));
  });

  test('登录后并行读取三类本人资源并可选择显式资源', async () => {
    const pages: Record<string, ResourcePage> = {
      orders: { items: [{ id: 'order-1', type: 'orders', status: 'PAID', createdAt: '2026-09-17T08:00:00Z' }], nextCursor: null },
      disputes: { items: [{ id: 'case-1', type: 'disputes', status: 'OPEN', createdAt: '2026-09-17T08:00:00Z' }], nextCursor: null },
      warranties: { items: [] }
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input).split('/').pop() ?? '';
      return new Response(JSON.stringify(pages[path]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    });
    const onSelect = vi.fn();

    render(<ResourcePicker token="short-test-token" onSelect={onSelect} />);

    await waitFor(() => expect(screen.getByRole('button', { name: /订单.*PAID/ })).toBeVisible());
    expect(globalThis.fetch).toHaveBeenCalledTimes(3);
    fireEvent.click(screen.getByRole('button', { name: /订单.*PAID/ }));

    expect(onSelect).toHaveBeenCalledWith({ kind: 'order', id: 'order-1', label: '订单' });
  });

  test('回答后的状态与来源位于服务台侧栏', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({
        answer: '已依据当前规则回答。',
        status: {
          id: 'order-1', type: 'orders', status: 'PAID',
          createdAt: '2026-09-17T08:00:00Z'
        },
        sources: [{ sourceId: 'refund-v1', title: '退款规则', version: 'v1' }]
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    );

    render(<AuthProvider><SupportDesk /></AuthProvider>);
    fireEvent.change(screen.getByRole('textbox', { name: '问题' }), {
      target: { value: '现在是什么状态？' }
    });
    fireEvent.click(screen.getByRole('button', { name: '获取规则答复' }));

    await waitFor(() => expect(screen.getByText('已依据当前规则回答。')).toBeVisible());
    const sideColumn = screen.getByRole('complementary', { name: '服务台侧栏' });
    expect(sideColumn).toContainElement(screen.getByRole('heading', { name: '当前状态' }));
    expect(sideColumn).toContainElement(screen.getByRole('heading', { name: '规则来源' }));
  });

  test('登出后迟到的私人答复不会落地', async () => {
    let releaseAnswer: ((response: Response) => void) | undefined;
    const pendingAnswer = new Promise<Response>((resolve) => {
      releaseAnswer = resolve;
    });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input);
      if (path.endsWith('/orders')) {
        return resourceResponse('orders');
      }
      if (path.endsWith('/disputes')) {
        return resourceResponse('disputes');
      }
      if (path.endsWith('/warranties')) {
        return resourceResponse('warranties');
      }
      return pendingAnswer;
    });

    render(<AuthProvider><SignedInDesk /></AuthProvider>);
    await waitFor(() => expect(screen.getByRole('button', { name: /订单.*PAID/ })).toBeVisible());
    fireEvent.click(screen.getByRole('button', { name: /订单.*PAID/ }));
    fireEvent.change(screen.getByRole('textbox', { name: '问题' }), {
      target: { value: '这个订单现在可以退款吗？' }
    });
    fireEvent.click(screen.getByRole('button', { name: '获取规则答复' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '退出登录' })).toBeVisible());

    fireEvent.click(screen.getByRole('button', { name: '退出登录' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: '登录后查询本人进度' })).toBeVisible());
    await act(async () => {
      releaseAnswer?.(new Response(JSON.stringify({
        answer: '迟到的私人答复',
        status: { id: 'order-1', type: 'orders', status: 'PAID', createdAt: '2026-09-17T08:00:00Z' },
        sources: []
      }), { status: 200 }));
      await Promise.resolve();
      await Promise.resolve();
    });

    await waitFor(() => expect(screen.queryByText('迟到的私人答复')).not.toBeInTheDocument());
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/ai/support/answers'))).toHaveLength(1);
  });

  test('私人请求收到 401 后不提供无 ID 的公开重试', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input);
      if (path.endsWith('/orders')) {
        return resourceResponse('orders');
      }
      if (path.endsWith('/disputes')) {
        return resourceResponse('disputes');
      }
      if (path.endsWith('/warranties')) {
        return resourceResponse('warranties');
      }
      return new Response(JSON.stringify({ code: 'UNAUTHENTICATED' }), { status: 401 });
    });

    render(<AuthProvider><SignedInDesk /></AuthProvider>);
    await waitFor(() => expect(screen.getByRole('button', { name: /订单.*PAID/ })).toBeVisible());
    fireEvent.click(screen.getByRole('button', { name: /订单.*PAID/ }));
    fireEvent.change(screen.getByRole('textbox', { name: '问题' }), {
      target: { value: '这个订单现在可以退款吗？' }
    });
    fireEvent.click(screen.getByRole('button', { name: '获取规则答复' }));

    await waitFor(() => expect(screen.getByRole('heading', { name: '登录后查询本人进度' })).toBeVisible());
    expect(screen.getByText('登录已失效，请重新登录。')).toBeVisible();
    expect(screen.getByRole('textbox', { name: '问题' })).toHaveValue('');
    expect(screen.queryByText(/已关联：/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/ai/support/answers'))).toHaveLength(1);
  });

  test('新查询开始及失败后不会保留旧答复和状态', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch');
    fetchMock
      .mockResolvedValueOnce(new Response(JSON.stringify({
        answer: '旧答复',
        status: { id: 'order-1', type: 'orders', status: 'PAID', createdAt: '2026-09-17T08:00:00Z' },
        sources: [{ sourceId: 'refund-v1', title: '退款规则', version: 'v1' }]
      }), { status: 200 }))
      .mockRejectedValueOnce(new Error('network down'));

    render(<AuthProvider><SupportDesk /></AuthProvider>);
    fireEvent.change(screen.getByRole('textbox', { name: '问题' }), {
      target: { value: '第一个问题' }
    });
    fireEvent.click(screen.getByRole('button', { name: '获取规则答复' }));
    await waitFor(() => expect(screen.getByText('旧答复')).toBeVisible());

    fireEvent.change(screen.getByRole('textbox', { name: '问题' }), {
      target: { value: '第二个问题' }
    });
    fireEvent.click(screen.getByRole('button', { name: '获取规则答复' }));
    await waitFor(() => expect(screen.queryByText('旧答复')).not.toBeInTheDocument());

    await waitFor(() => expect(screen.getByText('服务暂不可用')).toBeVisible());
    expect(screen.queryByText('旧答复')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '当前状态' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '规则来源' })).not.toBeInTheDocument();
  });
});

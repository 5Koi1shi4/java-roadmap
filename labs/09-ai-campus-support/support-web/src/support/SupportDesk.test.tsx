import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthProvider';
import type { AnswerResponse, ResourcePage } from '../api/client';
import { AnswerPanel } from './AnswerPanel';
import { ResourcePicker } from './ResourcePicker';
import { SupportDesk } from './SupportDesk';

describe('校园客服工作台', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

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
});

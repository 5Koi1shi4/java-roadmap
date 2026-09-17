import { expect, type APIRequestContext, type Page } from '@playwright/test';

const BASE_URL = process.env.SUPPORT_E2E_BASE_URL ?? 'http://127.0.0.1:18000';
const MAILPIT_URL = process.env.SUPPORT_E2E_MAILPIT_URL ?? 'http://127.0.0.1:8026';

export type TestUser = { email: string; password: string; token: string };

export async function registerCampusUserThroughTestMailFixture(
  request: APIRequestContext
): Promise<TestUser> {
  const email = `support-${crypto.randomUUID()}@stu.example.edu.cn`;
  const password = `Campus-${crypto.randomUUID()}-9a!`;
  const issued = await request.post(`${BASE_URL}/api/auth/email-verifications`, {
    data: { email, purpose: 'REGISTER' }
  });
  expect(issued.status(), 'verification request status').toBe(200);
  const cookie = issued.headers()['set-cookie']?.split(';', 1)[0];
  const code = await readVerificationCode(request, email);
  const registered = await request.post(`${BASE_URL}/api/auth/register`, {
    data: { email, password, code },
    headers: cookie ? { Cookie: cookie } : undefined
  });
  expect(registered.status()).toBe(201);
  const loggedIn = await request.post(`${BASE_URL}/api/auth/login`, {
    data: { email, password }
  });
  expect(loggedIn.ok()).toBeTruthy();
  const body = await loggedIn.json() as { accessToken: string };
  return { email, password, token: body.accessToken };
}

export async function createBuyerOrder(
  request: APIRequestContext,
  seller: TestUser,
  buyer: TestUser
): Promise<string> {
  const listingResponse = await request.post(`${BASE_URL}/api/listings`, {
    headers: bearer(seller.token),
    data: {
      title: 'Task 8 浏览器旅程教材', description: '仅供端到端验收', category: '教材',
      unitPriceFen: 100, availableQuantity: 2, sellerWarrantyDays: 30
    }
  });
  expect(listingResponse.status()).toBe(201);
  const listing = await listingResponse.json() as { id: string };
  const media = await request.post(`${BASE_URL}/api/listings/${listing.id}/media`, {
    headers: bearer(seller.token),
    multipart: {
      file: {
        name: 'journey.png',
        mimeType: 'image/png',
        buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2j1cAAAAASUVORK5CYII=', 'base64')
      }
    }
  });
  expect(media.status()).toBe(201);
  const published = await request.post(`${BASE_URL}/api/listings/${listing.id}/publish`, {
    headers: bearer(seller.token)
  });
  expect(published.ok()).toBeTruthy();
  const ordered = await request.post(`${BASE_URL}/api/orders`, {
    headers: { ...bearer(buyer.token), 'Idempotency-Key': crypto.randomUUID() },
    data: { listingId: listing.id, quantity: 1 }
  });
  expect(ordered.status()).toBe(201);
  const order = await ordered.json() as { id: string };
  return order.id;
}

export async function loginThroughPage(page: Page, user: TestUser): Promise<void> {
  await page.goto('/');
  await page.getByLabel('校园邮箱').fill(user.email);
  await page.getByLabel('密码').fill(user.password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '选择本人资源' })).toBeVisible();
}

export async function readVerificationCode(request: APIRequestContext, email: string): Promise<string> {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const response = await request.get(`${MAILPIT_URL}/api/v1/messages?limit=20`);
    if (response.ok()) {
      const list = await response.json() as { messages?: { ID: string }[] };
      for (const item of list.messages ?? []) {
        const message = await request.get(`${MAILPIT_URL}/api/v1/message/${item.ID}`);
        if (!message.ok()) continue;
        const body = await message.json() as { Text?: string; To?: { Address?: string }[] };
        if (body.To?.some((recipient) => recipient.Address === email)) {
          const code = body.Text?.match(/\b(\d{6})\b/)?.[1];
          if (code) return code;
        }
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error('测试邮件未在限定时间内到达');
}

function bearer(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

import { expect, test } from '@playwright/test';
import {
  createBuyerOrder,
  loginThroughPage,
  readVerificationCode,
  registerCampusUserThroughTestMailFixture
} from './fixtures';

test('注册表单通过演示邮件完成注册并进入本人资源区', async ({ page, request }) => {
  const email = `browser-${crypto.randomUUID()}@stu.example.edu.cn`;
  const password = `Campus-${crypto.randomUUID()}-9a!`;
  await page.goto('/');
  await page.getByRole('button', { name: '创建新账号' }).click();
  await page.getByLabel('校园邮箱').fill(email);
  await page.getByLabel('设置密码').fill(password);
  await page.getByRole('button', { name: '发送验证码' }).click();
  await expect(page.getByText('验证码已发送，请查收邮箱。')).toBeVisible();

  const code = await readVerificationCode(request, email);
  await page.getByLabel('注册验证码').fill(code);
  await page.getByLabel('注册验证码').press('Enter');
  await expect(page.getByRole('heading', { name: '选择本人资源' })).toBeVisible();
});

test('用户从登录到本人订单问答并登出', async ({ page, request }) => {
  const seller = await registerCampusUserThroughTestMailFixture(request);
  const buyer = await registerCampusUserThroughTestMailFixture(request);
  const stranger = await registerCampusUserThroughTestMailFixture(request);
  await createBuyerOrder(request, seller, buyer);

  await loginThroughPage(page, stranger);
  await expect(page.getByText('暂无本人订单。')).toBeVisible();
  await page.getByRole('button', { name: '退出登录' }).click();

  await loginThroughPage(page, buyer);
  await page.getByRole('button', { name: /订单 ·/ }).click();
  await page.getByRole('textbox', { name: '问题' }).fill('退款规则是什么');
  await page.getByRole('button', { name: '获取规则答复' }).click();
  await expect(page.getByRole('heading', { name: '当前状态' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '规则来源' })).toBeVisible();
  await expect(page.getByText('退款条件以公开规则为准。')).toBeVisible();
  await expect(page.getByText(/版本/).first()).toBeVisible();
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page.getByRole('heading', { name: '登录后查询本人进度' })).toBeVisible();
});

test('401 会清除会话并要求重新登录', async ({ page, request }) => {
  const user = await registerCampusUserThroughTestMailFixture(request);
  await loginThroughPage(page, user);
  await page.route('**/api/support/orders*', async (route) => {
    await route.fulfill({
      status: 401,
      contentType: 'application/json; charset=UTF-8',
      body: JSON.stringify({ code: 'UNAUTHENTICATED', message: '未认证' })
    });
  });

  await page.getByRole('button', { name: '刷新' }).click();
  await expect(page.getByText('登录已失效，请重新登录。')).toBeVisible();
  await expect(page.getByRole('heading', { name: '登录后查询本人进度' })).toBeVisible();
});

test('交易资源故障显示中文提示并可重试恢复', async ({ page, request }) => {
  const user = await registerCampusUserThroughTestMailFixture(request);
  let failOrders = true;
  await page.route('**/api/support/orders*', async (route) => {
    if (failOrders) {
      failOrders = false;
      await route.fulfill({
        status: 503,
        contentType: 'application/json; charset=UTF-8',
        body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', message: '交易服务不可用' })
      });
      return;
    }
    await route.continue();
  });

  await loginThroughPage(page, user);
  await expect(page.getByText('服务暂不可用')).toBeVisible();
  await page.getByRole('button', { name: '重试' }).click();
  await expect(page.getByText('暂无本人订单。')).toBeVisible();
});

test('模型故障显示中文提示并可重试恢复', async ({ page, request }) => {
  await page.goto('/');
  const controlledFailure = await request.post('http://127.0.0.1:18089/__control/fail');
  expect(controlledFailure.status()).toBe(204);
  await page.getByRole('textbox', { name: '问题' }).fill('退款规则是什么');
  await page.getByRole('button', { name: '获取规则答复' }).click();
  await expect(page.getByText('服务暂不可用')).toBeVisible();

  const controlledRecovery = await request.post('http://127.0.0.1:18089/__control/recover');
  expect(controlledRecovery.status()).toBe(204);
  await page.getByRole('button', { name: '重试' }).click();
  await expect(page.getByRole('heading', { name: '规则答复' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '规则来源' })).toBeVisible();
  await expect(page.getByText('退款条件以公开规则为准。')).toBeVisible();
  await expect(page.getByText(/版本/).first()).toBeVisible();
});

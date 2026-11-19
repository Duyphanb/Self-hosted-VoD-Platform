import { test, expect, type Page } from '@playwright/test';

test('real Compose session: sign in, reload, two tabs, sign out and offline retry', async ({ page, context, request }) => {
  const email = `browser-${Date.now()}-${test.info().project.name}@example.com`;
  const password = 'Browser-fixture-only!0931';
  const origin = new URL(test.info().project.use.baseURL!).origin;
  const registered = await request.post('/api/v1/auth/register', {
    headers: { Origin: origin }, data: { email, password, displayName: 'Browser Viewer' }
  });
  expect(registered.status()).toBe(201);
  const profile = await registered.json();
  const signIn = async (tab: Page) => {
    await tab.goto('/login');
    await tab.getByLabel('Email', { exact: true }).fill(email);
    await tab.getByLabel('Password', { exact: true }).fill(password);
    await tab.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(tab.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible();
  };
  const verifyMemory = async (tab: Page) => {
    await tab.goto('/account');
    await expect(tab.getByText('Signed in as Browser Viewer.', { exact: true })).toBeVisible();
    const marker = await tab.evaluate(() => JSON.parse(localStorage.getItem('vod.auth.marker.v2')!));
    expect(marker.userId).toBe(profile.id);
    expect(Object.keys(marker).sort()).toEqual(['generation', 'logoutPending', 'userId', 'version']);
    expect(await tab.evaluate(() => localStorage.getItem('vod.auth.session.v1'))).toBeNull();
    expect(await tab.evaluate(() => Object.keys(sessionStorage))).toEqual([]);
    expect(await tab.evaluate(() => indexedDB.databases().then(d => d.length))).toBe(0);
  };
  await signIn(page);
  await page.reload();
  await verifyMemory(page);
  const cookie = (await context.cookies()).find(c => c.name === 'vod_refresh');
  expect({ exists: !!cookie, secure: cookie?.secure, httpOnly: cookie?.httpOnly, sameSite: cookie?.sameSite, path: cookie?.path })
    .toEqual({ exists: true, secure: true, httpOnly: true, sameSite: 'Strict', path: '/api/v1/auth' });
  const other = await context.newPage();
  await verifyMemory(other);
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page.getByRole('link', { name: 'Sign in', exact: true })).toBeVisible();
  await other.bringToFront();
  await expect(other.getByRole('link', { name: 'Sign in', exact: true })).toBeVisible();
  expect((await context.cookies()).some(c => c.name === 'vod_refresh')).toBe(false);
  await signIn(page);
  // Keep the SPA reachable, but make its logout endpoint unreachable, including after reload.
  await context.route('**/api/v1/auth/logout', route => route.abort('internetdisconnected'));
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Sign-out is not confirmed.');
  await page.reload();
  await expect(page.getByRole('alert')).toContainText('Sign-out is not confirmed.');
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('vod.auth.marker.v2')!).logoutPending)).toBe(true);
  await context.unroute('**/api/v1/auth/logout');
  // Reconciliation leaves logout pending; only this explicit action retries it.
  await page.getByRole('button', { name: 'Retry sign-out', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveCount(0);
  expect((await context.cookies()).some(c => c.name === 'vod_refresh')).toBe(false);
  await page.reload();
  await expect(page.getByRole('link', { name: 'Sign in', exact: true })).toBeVisible();
});

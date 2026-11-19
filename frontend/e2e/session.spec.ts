import { test, expect, type Page, type BrowserContext, type APIRequestContext } from '@playwright/test';
const markerKey='vod.auth.marker.v2';
const state=async(request:APIRequestContext)=>(await request.get('/__test/state')).json();
const control=async(request:APIRequestContext,value:Record<string,unknown>)=>{await request.post('/__test/control',{data:value});};
async function identity(page:Page,user:string){await expect(page.getByTestId('identity')).toHaveText(user);}
async function signIn(page:Page,user='Alice'){
  await page.goto('/');await expect(page.getByTestId('initializing')).toHaveText('false');
  await page.getByRole('button',{name:'Sign in '+user,exact:true}).click();await identity(page,user.toLowerCase());
}
async function assertCookieAndMemory(context:BrowserContext,pages:Page[],user:string){
  for(const page of pages){await identity(page,user);
    const probe=await page.evaluate(()=>fetch('/api/v1/auth/probe').then(r=>r.json()));expect(probe.userId).toBe(user);}
  const cookie=(await context.cookies()).find(c=>c.name==='vod_refresh');
  expect({exists:!!cookie,httpOnly:cookie?.httpOnly,secure:cookie?.secure,sameSite:cookie?.sameSite,path:cookie?.path,
    hostOnly:!!cookie&&!cookie.domain.startsWith('.')}).toEqual({exists:true,httpOnly:true,secure:true,sameSite:'Strict',path:'/api/v1/auth',hostOnly:true});
}
async function noTokens(page:Page){
  expect(await page.evaluate(()=>{
    const values=[...Object.values(localStorage),...Object.values(sessionStorage)].join(' ');
    return /(?:access|refresh)-cookie-test-/.test(values);
  })).toBe(false);
  expect(await page.evaluate(()=>localStorage.getItem('vod.auth.session.v1'))).toBeNull();
  expect(await page.evaluate(()=>indexedDB.databases().then(databases=>databases.length))).toBe(0);
}
test.beforeEach(async({request})=>{await request.post('/__test/reset');});

test('browsers without Web Locks keep a visible single-tab fallback', async ({ page, context }) => {
  await page.addInitScript(() => Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true }));
  await signIn(page);
  await expect(page.getByRole('note')).toContainText('one tab at a time');
  await assertCookieAndMemory(context, [page], 'alice');
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await identity(page, 'signed-out');
  await expect.poll(async () => (await context.cookies()).some(c => c.name === 'vod_refresh')).toBe(false);
});

test('reload bootstraps exactly once; no tokens enter storage',async({page,context,request})=>{
  await page.addInitScript(()=>{if(!localStorage.getItem('vod.auth.marker.v2'))localStorage.setItem('vod.auth.session.v1','legacy-refresh-cookie-test-fixture');});
  await signIn(page);await noTokens(page);await assertCookieAndMemory(context,[page],'alice');
  await page.getByRole('button',{name:'Refresh session'}).click();await expect.poll(async()=>(await state(request)).active).toBe(0);
  await noTokens(page);const before=(await state(request)).counts.refresh;
  await page.reload();await identity(page,'alice');expect((await state(request)).counts.refresh).toBe(before+1);
  await noTokens(page);await assertCookieAndMemory(context,[page],'alice');
});

test('two tabs refresh serially and keep the actual cookie consistent',async({page,context,request})=>{
  await signIn(page);const other=await context.newPage();await other.goto('/');await identity(other,'alice');
  const before=(await state(request)).counts.refresh;
  await control(request,{hold:'refresh'});await page.getByRole('button',{name:'Refresh session'}).click();
  await expect.poll(async()=>(await state(request)).active).toBe(1);
  await other.getByRole('button',{name:'Refresh session'}).click();
  await expect.poll(async()=>(await state(request)).counts.refresh).toBe(before+1);
  await control(request,{release:true});await expect.poll(async()=>(await state(request)).counts.refresh).toBe(before+2);
  await expect.poll(async()=>(await state(request)).active).toBe(0);
  await assertCookieAndMemory(context,[page,other],'alice');expect((await state(request)).maxWriters).toBe(1);
});

test('login in another tab waits for refresh and replaces identity in every tab',async({page,context,request})=>{
  await signIn(page);const other=await context.newPage();await other.goto('/');await identity(other,'alice');
  await control(request,{hold:'refresh'});await page.getByRole('button',{name:'Refresh session'}).click();
  await expect.poll(async()=>(await state(request)).active).toBe(1);
  const before=(await state(request)).counts.login;
  await other.getByRole('button',{name:'Sign in Bob'}).click();expect((await state(request)).counts.login).toBe(before);
  await control(request,{release:true});await assertCookieAndMemory(context,[page,other],'bob');
  expect((await state(request)).maxWriters).toBe(1);await noTokens(page);await noTokens(other);
});

test('logout racing refresh leaves both tabs signed out and clears the cookie',async({page,context,request})=>{
  await signIn(page);const other=await context.newPage();await other.goto('/');await identity(other,'alice');
  await control(request,{hold:'refresh'});await page.getByRole('button',{name:'Refresh session'}).click();
  await expect.poll(async()=>(await state(request)).active).toBe(1);
  await other.getByRole('button',{name:'Sign out',exact:true}).click();await identity(other,'signed-out');
  await control(request,{release:true});await identity(page,'signed-out');
  await expect.poll(async()=>(await context.cookies()).some(c=>c.name==='vod_refresh')).toBe(false);
  expect((await state(request)).maxWriters).toBe(1);await noTokens(page);await noTokens(other);
});

test('reconciliation survives absent channels and a restored-tab pageshow',async({context,request})=>{
  await context.addInitScript(()=>{
    Object.defineProperty(window,'BroadcastChannel',{value:undefined,configurable:true});
    const original=window.addEventListener.bind(window);
    window.addEventListener=((name:string,listener:EventListenerOrEventListenerObject,options?:boolean|AddEventListenerOptions)=>{
      original(name,(event:Event)=>{
        if((window as unknown as {pauseWakeups:boolean}).pauseWakeups&&['storage','focus','visibilitychange'].includes(name))return;
        if(typeof listener==='function')listener(event);else listener.handleEvent(event);
      },options);
    }) as typeof window.addEventListener;
  });
  const page=await context.newPage();await signIn(page);const other=await context.newPage();await other.goto('/');await identity(other,'alice');
  await page.evaluate(()=>{(window as unknown as {pauseWakeups:boolean}).pauseWakeups=true;});
  await other.getByRole('button',{name:'Sign in Bob'}).click();await identity(other,'bob');
  await page.evaluate(()=>window.dispatchEvent(new PageTransitionEvent('pageshow',{persisted:true})));
  await assertCookieAndMemory(context,[page,other],'bob');expect((await state(request)).maxWriters).toBe(1);
});

test('an older-generation protected response is discarded before commit',async({page,context,request})=>{
  await signIn(page);const other=await context.newPage();await other.goto('/');await identity(other,'alice');
  await control(request,{holdRead:true});await page.getByRole('button',{name:'Protected request'}).click();
  await other.getByRole('button',{name:'Sign in Bob'}).click();await identity(other,'bob');
  await control(request,{release:true});await expect(page.getByTestId('result')).toHaveText('discarded');
});

test('unconfirmed sign-out survives reload and retries logout rather than refresh',async({page,context,request})=>{
  await signIn(page);await control(request,{logoutStatus:'drop'});
  await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.getByRole('button',{name:'Retry sign-out'})).toBeVisible();
  const before=(await state(request)).counts.refresh;
  await page.reload();await expect(page.getByRole('button',{name:'Retry sign-out'})).toBeVisible();await identity(page,'signed-out');
  expect((await state(request)).counts.refresh).toBe(before);
  await control(request,{logoutStatus:null});await page.getByRole('button',{name:'Retry sign-out'}).click();
  await expect.poll(async()=>(await context.cookies()).some(c=>c.name==='vod_refresh')).toBe(false);
  expect(await page.evaluate(key=>JSON.parse(localStorage.getItem(key)!).logoutPending,markerKey)).toBe(false);
});

test('two pending tabs do not retry failed logout on wake-ups', async ({ page, context, request }) => {
  await signIn(page);
  const other = await context.newPage();
  await other.goto('/');
  await identity(other, 'alice');
  await control(request, { logoutStatus: 503 });
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  for (const tab of [page, other]) {
    await identity(tab, 'signed-out');
    await expect(tab.getByRole('button', { name: 'Retry sign-out' })).toBeVisible();
  }
  await expect.poll(async () => (await state(request)).active).toBe(0);
  const before = (await state(request)).counts.logout;
  const pending = await page.evaluate(key => localStorage.getItem(key), markerKey);
  expect(before).toBe(1);
  for (const tab of [page, other]) {
    await tab.evaluate(async key => {
      for (let round = 0; round < 6; round += 1) {
        window.dispatchEvent(new StorageEvent('storage', { key }));
        window.dispatchEvent(new Event('focus'));
        document.dispatchEvent(new Event('visibilitychange'));
        window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));
        const channel = new BroadcastChannel('vod.auth.wakeup.v2');
        channel.postMessage('changed');
        channel.close();
        await new Promise(resolve => setTimeout(resolve, 250));
      }
    }, markerKey);
  }
  expect((await state(request)).counts.logout).toBe(before);
  expect(await other.evaluate(key => localStorage.getItem(key), markerKey)).toBe(pending);

  // A reload gets exactly one bootstrap attempt, without publishing a new generation.
  await other.reload();
  await expect.poll(async () => (await state(request)).counts.logout).toBe(before + 1);
  await expect.poll(async () => (await state(request)).active).toBe(0);
  expect(await other.evaluate(key => localStorage.getItem(key), markerKey)).toBe(pending);
  await control(request, { logoutStatus: null });
  await page.getByRole('button', { name: 'Retry sign-out' }).click();
  for (const tab of [page, other]) await expect(tab.getByRole('button', { name: 'Retry sign-out' })).toHaveCount(0);
  expect((await state(request)).counts.logout).toBe(before + 2);
  expect((await context.cookies()).some(cookie => cookie.name === 'vod_refresh')).toBe(false);
});

test('bootstrap outage is retryable and keeps the persistent identity',async({page,request})=>{
  await signIn(page);await control(request,{refreshStatus:503});await page.reload();
  await expect(page.getByRole('button',{name:'Retry sign-in'})).toBeVisible();
  expect(await page.evaluate(key=>JSON.parse(localStorage.getItem(key)!).userId,markerKey)).toBe('alice');
  await control(request,{refreshStatus:null});await page.getByRole('button',{name:'Retry sign-in'}).click();await identity(page,'alice');
});

test('blocked localStorage keeps a visible single-tab memory session',async({context})=>{
  await context.addInitScript(()=>Object.defineProperty(window,'localStorage',{get(){throw new DOMException('blocked','SecurityError');}}));
  const page=await context.newPage();await signIn(page);await expect(page.getByRole('note')).toContainText('Site data is blocked');
  await page.getByRole('button',{name:'Refresh session'}).click();await identity(page,'alice');
  expect(await page.evaluate(()=>sessionStorage.length)).toBe(0);
});

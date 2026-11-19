import { afterEach, expect, it, vi } from 'vitest';
import { withAuthSessionLock } from './authSessionLock';

afterEach(()=>{vi.useRealTimers();vi.unstubAllGlobals();});
it('serializes the single-tab fallback through response handling',async()=>{
  vi.stubGlobal('navigator',{});
  const order:string[]=[];let release!:()=>void;
  const gate=new Promise<void>(resolve=>{release=resolve;});
  const first=withAuthSessionLock(async()=>{order.push('first request');await gate;order.push('first response');});
  const second=withAuthSessionLock(async()=>{order.push('second request');});
  await Promise.resolve(); expect(order).toEqual(['first request']);
  release();await Promise.all([first,second]);expect(order).toEqual(['first request','first response','second request']);
});
it('starts its timeout after acquiring the web lock and aborts before release',async()=>{
  vi.useFakeTimers();let enter!:()=>Promise<void>;const order:string[]=[];
  vi.stubGlobal('navigator',{locks:{request:vi.fn((_name,callback:()=>Promise<void>)=>new Promise<void>((resolve,reject)=>{
    enter=async()=>{try {await callback();resolve();} catch(error) {reject(error);} finally {order.push('released');}};
  }))}});
  const operation=withAuthSessionLock(async signal=>{
    await new Promise<void>(resolve=>signal.addEventListener('abort',()=>{order.push('aborted');resolve();}));
  },100);
  const rejected=expect(operation).rejects.toThrow('timed out');
  await vi.advanceTimersByTimeAsync(1000);expect(order).toEqual([]);
  const entered=enter();await vi.advanceTimersByTimeAsync(100);await entered;await rejected;
  expect(order).toEqual(['aborted','released']);
});

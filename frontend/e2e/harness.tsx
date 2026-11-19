import { StrictMode, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useApiClient, useAuth } from '../src/features/auth/AuthContext';
import { AuthenticationSupersededError } from '../src/lib/api/client';
export function Harness() {
  const auth=useAuth(), client=useApiClient(); const [result,setResult]=useState('none');
  const run=(operation:()=>Promise<unknown>)=>{void operation().catch(()=>undefined);};
  return <main>
    <p data-testid="identity">{auth.user?.id ?? 'signed-out'}</p>
    <p data-testid="initializing">{String(auth.isInitializing)}</p>
    <button onClick={()=>run(()=>auth.login({email:'alice@example.com',password:'fixture-password'}))}>Sign in Alice</button>
    <button onClick={()=>run(()=>auth.login({email:'bob@example.com',password:'fixture-password'}))}>Sign in Bob</button>
    <button onClick={()=>run(()=>auth.refreshTokens())}>Refresh session</button>
    <button onClick={()=>run(()=>auth.logout())}>Sign out</button>
    <button onClick={()=>run(async()=>{
      try {const user=await client.request<{id:string}>('/users/me',{authenticated:true});setResult(user.id);}
      catch(error){setResult(error instanceof AuthenticationSupersededError?'discarded':'failed');}
    })}>Protected request</button>
    <p data-testid="result">{result}</p>
  </main>;
}
createRoot(document.getElementById('root')!).render(<StrictMode><QueryClientProvider client={new QueryClient()}>
  <AuthProvider redirectToLogin={()=>undefined}><Harness/></AuthProvider>
</QueryClientProvider></StrictMode>);

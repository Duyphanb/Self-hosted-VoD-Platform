import type { ReactNode } from 'react';
import { createBrowserRouter, type RouteObject } from 'react-router-dom';
import { AppProviders } from './AppProviders';
import { AppRoutes } from './AppRoutes';

export const APP_ROUTER_FUTURE = { v7_relativeSplatPath: true } as const;
export const APP_ROUTER_PROVIDER_FUTURE = { v7_startTransition: true } as const;

/** Production and memory-router integration tests share the complete provider/route composition. */
export function createAppRoutes(extraContent?: ReactNode): RouteObject[] {
  return [{ path: '*', element: <AppProviders>{extraContent}<AppRoutes /></AppProviders> }];
}

export function createAppBrowserRouter() {
  return createBrowserRouter(createAppRoutes(), { future: APP_ROUTER_FUTURE });
}

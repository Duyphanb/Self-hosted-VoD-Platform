import React from 'react';
import ReactDOM from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { AppProviders } from './app/AppProviders';
import { AppRoutes } from './app/AppRoutes';
import './styles.css';

// The data router provides useBlocker for unsaved admin forms, including Back/Forward navigation.
const router = createBrowserRouter([{
  path: '*',
  element: <AppProviders><AppRoutes /></AppProviders>
}], { future: { v7_relativeSplatPath: true } });

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <RouterProvider future={{ v7_startTransition: true }} router={router} />
  </React.StrictMode>
);

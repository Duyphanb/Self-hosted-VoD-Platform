import React from 'react';
import ReactDOM from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import { APP_ROUTER_PROVIDER_FUTURE, createAppBrowserRouter } from './app/AppRouter';
import './styles.css';

// The data router provides useBlocker for unsaved admin forms, including Back/Forward navigation.
const router = createAppBrowserRouter();

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <RouterProvider future={APP_ROUTER_PROVIDER_FUTURE} router={router} />
  </React.StrictMode>
);

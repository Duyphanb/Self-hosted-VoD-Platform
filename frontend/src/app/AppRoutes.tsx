import { Route, Routes } from 'react-router-dom';
import { AppLayout } from '../components/AppLayout';
import { AdminRoute, GuestRoute, ProtectedRoute } from '../features/auth/RouteGuards';
import { LoginPage } from '../features/auth/pages/LoginPage';
import { RegisterPage } from '../features/auth/pages/RegisterPage';
import { MovieDetailPage } from '../features/movies/pages/MovieDetailPage';
import { MovieListPage } from '../features/movies/pages/MovieListPage';
import { AccountPage, AdminPage, ForbiddenPage } from '../pages/AccessPages';
import { HomePage } from '../pages/HomePage';
import { NotFoundPage } from '../pages/NotFoundPage';

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route element={<GuestRoute signedInPath="/movies" />}>
          <Route index element={<HomePage />} />
        </Route>
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="forbidden" element={<ForbiddenPage />} />
        <Route element={<ProtectedRoute />}>
          <Route path="movies" element={<MovieListPage />} />
          <Route path="movies/:movieId" element={<MovieDetailPage />} />
          <Route path="account" element={<AccountPage />} />
        </Route>
        <Route element={<AdminRoute />}>
          <Route path="admin" element={<AdminPage />} />
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

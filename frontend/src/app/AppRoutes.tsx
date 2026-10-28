import { Navigate, Route, Routes } from 'react-router-dom';
import { AppLayout } from '../components/AppLayout';
import { AdminRoute, GuestRoute, ProtectedRoute } from '../features/auth/RouteGuards';
import { LoginPage } from '../features/auth/pages/LoginPage';
import { RegisterPage } from '../features/auth/pages/RegisterPage';
import { MovieDetailPage } from '../features/movies/pages/MovieDetailPage';
import { MovieListPage } from '../features/movies/pages/MovieListPage';
import { AdminMovieListPage } from '../features/movies/pages/AdminMovieListPage';
import { AdminMovieFormPage } from '../features/movies/pages/AdminMovieFormPage';
import { AccountPage, ForbiddenPage } from '../pages/AccessPages';
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
          <Route path="admin" element={<Navigate replace to="/admin/movies" />} />
          <Route path="admin/movies" element={<AdminMovieListPage />} />
          <Route path="admin/movies/new" element={<AdminMovieFormPage />} />
          <Route path="admin/movies/:movieId/edit" element={<AdminMovieFormPage />} />
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

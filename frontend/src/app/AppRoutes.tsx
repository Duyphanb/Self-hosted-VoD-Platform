import { Navigate, Route, Routes } from 'react-router-dom';
import { AppLayout } from '../components/AppLayout';
import { AdminRoute, GuestRoute, ProtectedRoute } from '../features/auth/RouteGuards';
import { LoginPage } from '../features/auth/pages/LoginPage';
import { RegisterPage } from '../features/auth/pages/RegisterPage';
import { MovieDetailPage } from '../features/movies/pages/MovieDetailPage';
import { MovieListPage } from '../features/movies/pages/MovieListPage';
import { LegacyTitleRedirect, LegacyWatchRedirect } from '../features/movies/LegacyTitleRoutes';
import { AdminMovieListPage } from '../features/movies/pages/AdminMovieListPage';
import { AdminMovieFormPage } from '../features/movies/pages/AdminMovieFormPage';
import { AdminMovieUploadPage } from '../features/movies/pages/AdminMovieUploadPage';
import { WatchPage } from '../features/player/pages/WatchPage';
import { ContinueWatchingPage } from '../features/progress/pages/ContinueWatchingPage';
import { AccountPage, ForbiddenPage } from '../pages/AccessPages';
import { HomePage } from '../pages/HomePage';
import { NotFoundPage } from '../pages/NotFoundPage';

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route element={<GuestRoute signedInPath="/titles" />}>
          <Route index element={<HomePage />} />
        </Route>
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="forbidden" element={<ForbiddenPage />} />
        <Route element={<ProtectedRoute />}>
          <Route path="titles" element={<MovieListPage />} />
          <Route path="titles/:movieId" element={<MovieDetailPage />} />
          <Route path="watch/:episodeId" element={<WatchPage />} />
          <Route path="movies" element={<LegacyTitleRedirect />} />
          <Route path="movies/:movieId" element={<LegacyTitleRedirect />} />
          <Route path="movies/:movieId/watch" element={<LegacyWatchRedirect />} />
          <Route path="continue-watching" element={<ContinueWatchingPage />} />
          <Route path="account" element={<AccountPage />} />
        </Route>
        <Route element={<AdminRoute />}>
          <Route path="admin" element={<Navigate replace to="/admin/titles" />} />
          <Route path="admin/titles" element={<AdminMovieListPage />} />
          <Route path="admin/titles/new" element={<AdminMovieFormPage />} />
          <Route path="admin/titles/:movieId/edit" element={<AdminMovieFormPage />} />
          <Route path="admin/titles/:movieId/video" element={<AdminMovieUploadPage />} />
          <Route path="admin/movies/*" element={<LegacyTitleRedirect />} />
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

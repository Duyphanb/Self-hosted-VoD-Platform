import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApiClient } from '../auth/AuthContext';
import { listMovies, movieKeys, type MovieListQuery } from './moviesApi';

/** Published movie page; the previous page stays visible while the next one loads. */
export function useMovieList(query: MovieListQuery) {
  const apiClient = useApiClient();
  return useQuery({
    queryKey: movieKeys.list(query),
    queryFn: ({ signal }) => listMovies(apiClient, query, signal),
    placeholderData: keepPreviousData
  });
}

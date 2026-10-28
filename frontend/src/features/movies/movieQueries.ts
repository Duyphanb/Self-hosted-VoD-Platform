import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../auth/AuthContext';
import { listMovies, movieKeys, type MovieListQuery, type MoviePage } from './moviesApi';

/**
 * Published movie page. While another page of the same filters loads, the previous movies stay on
 * screen. A filter change, or a previous result without movies, shows the loading state instead, so
 * a result or pagination that belongs to other filters is never shown or clickable.
 */
export function useMovieList(query: MovieListQuery) {
  const apiClient = useApiClient();
  return useQuery({
    queryKey: movieKeys.list(query),
    queryFn: ({ signal }) => listMovies(apiClient, query, signal),
    placeholderData: (previousData: MoviePage | undefined, previousQuery) => (
      previousData !== undefined
        && previousData.items.length > 0
        && previousQuery !== undefined
        && sameFilters(previousQuery.queryKey[2], query)
        ? previousData
        : undefined
    )
  });
}

function sameFilters(left: MovieListQuery, right: MovieListQuery): boolean {
  return left.genre === right.genre
    && left.year === right.year
    && left.sort === right.sort
    && left.size === right.size;
}

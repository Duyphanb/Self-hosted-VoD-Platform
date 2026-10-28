import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../auth/AuthContext';
import { listAdminGenres, listAdminMovies, searchPeople, type AdminMovieListQuery } from './adminMoviesApi';
import { movieKeys, type MoviePage } from './moviesApi';

export const adminMovieKeys = {
  list: (query: AdminMovieListQuery) => [...movieKeys.all, 'admin-list', query] as const
};

export const adminCatalogKeys = {
  all: ['admin-catalog'] as const,
  genres: ['admin-catalog', 'genres'] as const,
  people: (q: string, page: number) => ['admin-catalog', 'people', q, page] as const
};

export function useAdminMovies(query: AdminMovieListQuery) {
  const apiClient = useApiClient();
  return useQuery({
    queryKey: adminMovieKeys.list(query),
    queryFn: ({ signal }) => listAdminMovies(apiClient, query, signal),
    placeholderData: (previousData: MoviePage | undefined, previousQuery) => {
      const previous = previousQuery?.queryKey[2];
      return previousData?.items.length && previous && previous.status === query.status
        && previous.sort === query.sort && previous.size === query.size ? previousData : undefined;
    }
  });
}

export function useAdminGenres() {
  const apiClient = useApiClient();
  return useQuery({ queryKey: adminCatalogKeys.genres, queryFn: ({ signal }) => listAdminGenres(apiClient, signal) });
}

export function usePeopleSearch(q: string, page = 0, enabled = true) {
  const apiClient = useApiClient();
  return useQuery({
    queryKey: adminCatalogKeys.people(q, page),
    queryFn: ({ signal }) => searchPeople(apiClient, q, page, signal),
    enabled: enabled && [...q].length <= 100
  });
}

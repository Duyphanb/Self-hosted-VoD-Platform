export type UserRole = 'ROLE_USER' | 'ROLE_ADMIN';

export interface UserProfile {
  id: string;
  email: string;
  displayName: string;
  roles: UserRole[];
}

export interface AuthResponse {
  user: UserProfile;
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
}

/** Pagination metadata of every paged response; `page` is 0-based like the request parameter. */
export interface PageMeta {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ApiFieldError {
  field: string;
  message: string;
}

export interface ApiError {
  timestamp: string;
  status: number;
  code: string;
  message: string;
  requestId?: string | null;
  fieldErrors?: ApiFieldError[];
}

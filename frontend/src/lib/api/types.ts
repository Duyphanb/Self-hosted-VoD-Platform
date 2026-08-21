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

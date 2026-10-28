import { ApiRequestError } from './client';

export interface ErrorDetails {
  message: string;
  requestId: string | null;
}

const UNEXPECTED_FAILURE_MESSAGE = 'The server could not be reached or sent an unexpected response.';

/** User-facing message and support request id of a failed API call. */
export function errorDetails(error: unknown): ErrorDetails {
  if (error instanceof ApiRequestError) {
    return { message: error.message, requestId: error.apiError?.requestId ?? null };
  }
  return { message: UNEXPECTED_FAILURE_MESSAGE, requestId: null };
}

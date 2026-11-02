import { useEffect, useLayoutEffect, useReducer, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ApiRequestError, AuthenticationSupersededError } from '../../lib/api/client';
import { useApiClient } from '../auth/AuthContext';
import { uploadMovieVideo } from './adminMoviesApi';
import { getMovie, movieKeys } from './moviesApi';
import { checkVideoFile, classifyUploadFailure, uploadPercent, type UploadFailure } from './videoUpload';

type UploadPhase = 'idle' | 'checking' | 'sending' | 'saving' | 'terminal';
export type VideoUploadOutcome = UploadFailure | {
  kind: 'accepted' | 'notSent'; message: string; requestId: string | null; serverMessage: string | null;
};
interface UploadState {
  phase: UploadPhase;
  file: File | null;
  validation: string | null;
  percent: number | null;
  outcome: VideoUploadOutcome | null;
  revision: number;
}
type Action = { type: 'select'; file: File | null; validation: string | null }
  | { type: 'phase'; phase: 'checking' | 'sending' | 'saving'; percent?: number | null }
  | { type: 'outcome'; outcome: VideoUploadOutcome };

function reducer(state: UploadState, action: Action): UploadState {
  if (action.type === 'select') return { ...state, file: action.file, validation: action.validation, phase: 'idle', outcome: null, percent: null };
  if (action.type === 'phase') return { ...state, phase: action.phase, percent: action.percent ?? null, outcome: null };
  return { ...state, phase: 'terminal', outcome: action.outcome, percent: null,
    file: action.outcome.kind === 'accepted' ? null : state.file, revision: state.revision + 1 };
}

/** One explicit click owns one fresh GET and at most one upload; no mutation retries. */
export function useVideoUpload(movieId: string) {
  const [state, dispatch] = useReducer(reducer, {
    phase: 'idle', file: null, validation: null, percent: null, outcome: null, revision: 0
  });
  const apiClient = useApiClient();
  const queryClient = useQueryClient();
  const mounted = useRef(false);
  const inFlight = useRef(false);
  const phase = useRef<UploadPhase>('idle');
  const active = useRef<AbortController | null>(null);
  const userCancelled = useRef(false);
  const statusRef = useRef<HTMLDivElement>(null);
  const outcomeRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      if (phase.current === 'checking' || phase.current === 'sending') active.current?.abort();
    };
  }, []);
  useLayoutEffect(() => {
    if (state.revision > 0) outcomeRef.current?.focus();
  }, [state.revision]);

  const selectFile = (file: File | null) => {
    if (inFlight.current) return;
    const validation = file ? checkVideoFile(file) : null;
    dispatch({ type: 'select', file, validation: validation && !validation.valid ? validation.message : null });
  };

  const finish = (outcome: VideoUploadOutcome) => {
    if (!mounted.current) return;
    phase.current = 'terminal';
    dispatch({ type: 'outcome', outcome });
  };

  const submit = async () => {
    if (inFlight.current || !state.file) return;
    const checked = checkVideoFile(state.file);
    if (!checked.valid) {
      dispatch({ type: 'select', file: state.file, validation: checked.message });
      return;
    }
    inFlight.current = true;
    phase.current = 'checking';
    userCancelled.current = false;
    const controller = new AbortController();
    active.current = controller;
    // Move away from the submit button before it becomes disabled.
    statusRef.current?.focus();
    dispatch({ type: 'phase', phase: 'checking' });
    try {
      try {
        // Do not use the display query/cache: every click must check current access.
        await getMovie(apiClient, movieId, controller.signal);
      } catch (error) {
        if (!mounted.current) return;
        if (error instanceof AuthenticationSupersededError) finish(classifyUploadFailure(error));
        else if (error instanceof ApiRequestError && [400, 404].includes(error.status)) {
          finish({ kind: 'notFound', message: 'Movie not found', requestId: error.apiError?.requestId ?? null, serverMessage: error.apiError?.message ?? null });
        } else if (error instanceof ApiRequestError && error.status === 403) {
          finish({ kind: 'forbidden', message: 'Access forbidden', requestId: error.apiError?.requestId ?? null, serverMessage: error.apiError?.message ?? null });
        } else finish({ kind: 'notSent', message: 'Movie could not be checked. The file was not sent.',
          requestId: error instanceof ApiRequestError ? error.apiError?.requestId ?? null : null,
          serverMessage: error instanceof ApiRequestError ? error.apiError?.message ?? null : null });
        return;
      }
      if (!mounted.current || controller.signal.aborted) return;
      phase.current = 'sending';
      dispatch({ type: 'phase', phase: 'sending', percent: 0 });
      try {
        const result = await uploadMovieVideo(apiClient, movieId, state.file, checked.declaredType, {
          signal: controller.signal,
          onProgress: (loaded, total) => {
            if (mounted.current && phase.current === 'sending') dispatch({ type: 'phase', phase: 'sending', percent: uploadPercent(loaded, total) });
          },
          onBodySent: () => {
            if (!mounted.current) return;
            // Keep focus in the status region when Cancel is removed.
            const focused = document.activeElement;
            if (!focused || focused === document.body || focused.id === 'cancel-video-upload') statusRef.current?.focus();
            phase.current = 'saving';
            dispatch({ type: 'phase', phase: 'saving' });
          }
        });
        // An accepted write still invalidates queries if this page was unmounted.
        void queryClient.invalidateQueries({ queryKey: movieKeys.all }).catch(() => undefined);
        if (!mounted.current) return;
        if (fileInputRef.current) fileInputRef.current.value = '';
        finish({ kind: 'accepted', message: result.response
          ? `Upload accepted. Video asset status: ${result.response.status}.`
          : 'Upload accepted, status could not be read.', requestId: null, serverMessage: null });
      } catch (error) {
        if (mounted.current) finish(classifyUploadFailure(error, { userCancelled: userCancelled.current }));
      }
    } finally {
      inFlight.current = false;
      if (active.current === controller) active.current = null;
    }
  };

  const cancel = (): boolean => {
    if (phase.current !== 'sending' || !active.current) return false;
    userCancelled.current = true;
    active.current.abort();
    return true;
  };

  return { ...state, pending: ['checking', 'sending', 'saving'].includes(state.phase),
    canUpload: state.file !== null && state.validation === null && !['checking', 'sending', 'saving'].includes(state.phase),
    selectFile, submit, cancel, statusRef, outcomeRef, fileInputRef };
}

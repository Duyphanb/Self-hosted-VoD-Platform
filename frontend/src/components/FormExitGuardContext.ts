import { createContext } from 'react';

/** Lets a mounted form confirm a deliberate sign-out before the session is cleared. */
export type FormExitGuard = () => Promise<boolean>;
export const FormExitGuardContext = createContext<((guard: FormExitGuard) => () => void) | null>(null);

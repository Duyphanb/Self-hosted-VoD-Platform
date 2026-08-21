import { type FormEvent, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import type { UserProfile } from '../../../lib/api/types';
import { useAuth } from '../AuthContext';
import type { RegisterRequest } from '../authApi';
import {
  authFormFailure,
  type AuthFieldErrors,
  validateRegistrationForm
} from '../authForm';
import { AuthField, AuthFormCard, SubmitButton } from '../components/AuthFormCard';

type RegistrationField = keyof RegisterRequest;

const REGISTRATION_FIELDS: readonly RegistrationField[] = ['email', 'password', 'displayName'];
const INITIAL_VALUES: RegisterRequest = { email: '', password: '', displayName: '' };

export function RegisterPage() {
  const { register, clearError } = useAuth();
  const [values, setValues] = useState(INITIAL_VALUES);
  const [fieldErrors, setFieldErrors] = useState<AuthFieldErrors<RegistrationField>>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [registeredUser, setRegisteredUser] = useState<UserProfile | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const submitInFlight = useRef(false);

  const updateField = (field: RegistrationField, value: string) => {
    setValues((current) => ({ ...current, [field]: value }));
    setFieldErrors((current) => ({ ...current, [field]: undefined }));
    setServerError(null);
    clearError();
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitInFlight.current) {
      return;
    }
    const validationErrors = validateRegistrationForm(values);
    setFieldErrors(validationErrors);
    setServerError(null);
    clearError();
    if (Object.keys(validationErrors).length > 0) {
      return;
    }

    submitInFlight.current = true;
    setIsSubmitting(true);
    try {
      const profile = await register(values);
      setValues(INITIAL_VALUES);
      setRegisteredUser(profile);
    } catch (failure) {
      const formFailure = authFormFailure(failure, REGISTRATION_FIELDS);
      setFieldErrors(formFailure.fieldErrors);
      setServerError(formFailure.message);
    } finally {
      submitInFlight.current = false;
      setIsSubmitting(false);
    }
  };

  if (registeredUser) {
    return (
      <section className="mx-auto w-full max-w-md rounded-lg border border-emerald-700 bg-emerald-950/40 p-8 text-center">
        <div aria-hidden="true" className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-emerald-500/20 text-2xl text-emerald-300">
          ✓
        </div>
        <h1 className="mt-4 text-2xl font-semibold text-white">Account created</h1>
        <p
          className="mt-3 text-sm leading-6 text-emerald-100"
          role="status"
        >
          Account created for {registeredUser.email}.
        </p>
        <p className="mt-2 text-sm text-slate-300">Sign in to start your session.</p>
        <Link
          className="mt-6 inline-flex rounded-md bg-sky-500 px-4 py-2.5 text-sm font-semibold text-slate-950 hover:bg-sky-400"
          to="/login"
        >
          Sign in
        </Link>
        <button
          className="mt-4 block w-full text-sm font-medium text-slate-300 hover:text-white"
          onClick={() => setRegisteredUser(null)}
          type="button"
        >
          Create another account
        </button>
      </section>
    );
  }

  return (
    <AuthFormCard
      alternateLinkText="Sign in"
      alternateText="Already have an account?"
      alternateTo="/login"
      description="Create a viewer account. You will sign in after registration."
      title="Create account"
    >
      <form aria-busy={isSubmitting} className="grid gap-5" noValidate onSubmit={submit}>
        {serverError ? (
          <p className="rounded-md border border-rose-500/50 bg-rose-500/10 p-3 text-sm text-rose-200" role="alert">
            {serverError}
          </p>
        ) : null}
        <AuthField
          autoComplete="email"
          error={fieldErrors.email}
          label="Email"
          maxLength={320}
          name="email"
          onChange={(event) => updateField('email', event.target.value)}
          required
          type="email"
          value={values.email}
        />
        <AuthField
          autoComplete="name"
          error={fieldErrors.displayName}
          label="Display name"
          maxLength={100}
          minLength={2}
          name="displayName"
          onChange={(event) => updateField('displayName', event.target.value)}
          required
          type="text"
          value={values.displayName}
        />
        <AuthField
          autoComplete="new-password"
          error={fieldErrors.password}
          label="Password"
          maxLength={72}
          minLength={8}
          name="password"
          onChange={(event) => updateField('password', event.target.value)}
          required
          type="password"
          value={values.password}
        />
        <SubmitButton
          idleLabel="Create account"
          loading={isSubmitting}
          loadingLabel="Creating account…"
          loadingStatusLabel="Creating account"
        />
      </form>
    </AuthFormCard>
  );
}

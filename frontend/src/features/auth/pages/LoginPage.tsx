import { type FormEvent, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../AuthContext';
import type { LoginRequest } from '../authApi';
import {
  authFormFailure,
  type AuthFieldErrors,
  resolvePostLoginPath,
  validateLoginForm
} from '../authForm';
import { AuthField, AuthFormCard, SubmitButton } from '../components/AuthFormCard';

type LoginField = keyof LoginRequest;

const LOGIN_FIELDS: readonly LoginField[] = ['email', 'password'];
const INITIAL_VALUES: LoginRequest = { email: '', password: '' };

export function LoginPage() {
  const { login, clearError } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [values, setValues] = useState(INITIAL_VALUES);
  const [fieldErrors, setFieldErrors] = useState<AuthFieldErrors<LoginField>>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const submitInFlight = useRef(false);

  const updateField = (field: LoginField, value: string) => {
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
    const validationErrors = validateLoginForm(values);
    setFieldErrors(validationErrors);
    setServerError(null);
    clearError();
    if (Object.keys(validationErrors).length > 0) {
      return;
    }

    submitInFlight.current = true;
    setIsSubmitting(true);
    try {
      await login(values);
      navigate(resolvePostLoginPath(location.state), { replace: true });
    } catch (failure) {
      const formFailure = authFormFailure(failure, LOGIN_FIELDS);
      setFieldErrors(formFailure.fieldErrors);
      setServerError(formFailure.message);
    } finally {
      submitInFlight.current = false;
      setIsSubmitting(false);
    }
  };

  return (
    <AuthFormCard
      alternateLinkText="Create an account"
      alternateText="New to VoD Platform?"
      alternateTo="/register"
      description="Use your account credentials to continue."
      title="Sign in"
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
          autoComplete="current-password"
          error={fieldErrors.password}
          label="Password"
          name="password"
          onChange={(event) => updateField('password', event.target.value)}
          required
          type="password"
          value={values.password}
        />
        <SubmitButton
          idleLabel="Sign in"
          loading={isSubmitting}
          loadingLabel="Signing in…"
          loadingStatusLabel="Signing in"
        />
      </form>
    </AuthFormCard>
  );
}

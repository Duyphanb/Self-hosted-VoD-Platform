import { ApiRequestError } from '../../lib/api/client';
import { slugify } from '../../lib/slug';
import { isCanonicalUuid } from '../../lib/uuid';
import type { UpsertMovieRequest } from './adminMoviesApi';
import { CREDIT_ROLES, MATURITY_RATINGS, MOVIE_STATUSES, type CreditRole, type MaturityRating, type MovieDetail, type MovieStatus } from './moviesApi';

export interface MovieFormCredit { key: string; personId: string; name: string; role: string }
export interface MovieFormValues {
  title: string;
  slug: string;
  description: string;
  releaseYear: string;
  maturityRating: string;
  genres: string[];
  people: MovieFormCredit[];
  status: string;
}
export type MovieFormErrors = Record<string, string>;
export interface MovieFormFailure { fields: MovieFormErrors; general: string[] }

export function createEmptyMovieForm(): MovieFormValues {
  return { title: '', slug: '', description: '', releaseYear: '', maturityRating: '', genres: [], people: [], status: 'DRAFT' };
}

export function movieDetailToForm(movie: MovieDetail): MovieFormValues {
  return {
    title: movie.title, slug: movie.slug, description: movie.description,
    releaseYear: movie.releaseYear === null ? '' : String(movie.releaseYear),
    maturityRating: movie.maturityRating ?? '', genres: [...movie.genres], status: movie.status,
    people: movie.people.map((credit, index) => ({ ...credit, key: `${credit.personId}-${credit.role}-${index}` }))
  };
}

/** Full replacement, including explicit nulls and ordered credit IDs; UI-only row keys are omitted. */
export function toMoviePayload(values: MovieFormValues): UpsertMovieRequest {
  return {
    title: trimCatalogText(values.title), slug: values.slug, description: trimCatalogText(values.description),
    releaseYear: values.releaseYear.trim() === '' ? null : Number(values.releaseYear),
    maturityRating: values.maturityRating === '' ? null : values.maturityRating as MaturityRating,
    genres: values.genres.map(normalizeCatalogName),
    people: values.people.map((credit) => ({ personId: credit.personId.toLowerCase(), role: credit.role as CreditRole })),
    status: values.status as MovieStatus
  };
}

export function validateMovieForm(values: MovieFormValues): MovieFormErrors {
  const errors: MovieFormErrors = {};
  const titleError = requiredText(values.title, 'Title', 255, false, trimCatalogText(values.title));
  if (titleError) errors.title = titleError;
  if ([...values.slug].length > 255 || !/^[a-z0-9]+(-[a-z0-9]+)*$/.test(values.slug)) {
    errors.slug = 'Enter a slug of 1–255 lowercase letters, digits, and single hyphens.';
  }
  const descriptionError = requiredText(values.description, 'Description', 5000, true, trimCatalogText(values.description));
  if (descriptionError) errors.description = descriptionError;
  const year = values.releaseYear.trim();
  if (year !== '' && (!/^\d+$/.test(year) || !Number.isSafeInteger(Number(year)) || Number(year) < 1888 || Number(year) > 2100)) {
    errors.releaseYear = 'Enter a whole release year from 1888 to 2100.';
  }
  if (values.maturityRating !== '' && !MATURITY_RATINGS.some((rating) => rating === values.maturityRating)) {
    errors.maturityRating = 'Choose a supported maturity rating.';
  }
  if (!MOVIE_STATUSES.some((status) => status === values.status)) errors.status = 'Choose a supported movie status.';
  if (values.genres.length < 1 || values.genres.length > 10) errors.genres = 'Choose 1–10 genres.';
  const genreSlugs = new Set<string>();
  values.genres.forEach((name, index) => {
    const field = `genres[${index}]`;
    const error = validateGenreName(name);
    if (error) errors[field] = error;
    else if (genreSlugs.has(slugify(name))) errors[field] = 'This genre is already selected.';
    else genreSlugs.add(slugify(name));
  });
  if (values.people.length > 50) errors.people = 'Choose at most 50 credits.';
  const credits = new Set<string>();
  values.people.forEach((credit, index) => {
    const field = `people[${index}]`;
    if (!isCanonicalUuid(credit.personId.toLowerCase())) errors[`${field}.personId`] = 'Pick an existing person or explicitly create a new person.';
    if (!CREDIT_ROLES.some((role) => role === credit.role)) errors[`${field}.role`] = 'Choose Actor or Director.';
    const identity = `${credit.personId.toLowerCase()}-${credit.role}`;
    if (credit.personId && credits.has(identity)) errors[field] = 'This person already has this role in the credits.';
    else credits.add(identity);
  });
  return errors;
}

export function normalizeCatalogName(value: string): string {
  return trimCatalogText(value).replace(/\p{White_Space}+/gu, ' ');
}

export function validateGenreName(value: string): string | null {
  const error = requiredText(value, 'Genre name', 100, false, normalizeCatalogName(value));
  return error ?? (slugify(normalizeCatalogName(value)) ? null : 'Genre name must contain a letter or digit.');
}

export function validatePersonName(value: string): string | null {
  return requiredText(value, 'Person name', 255);
}

export function mapMovieFormError(error: unknown, values?: Pick<MovieFormValues, 'genres' | 'people'>): MovieFormFailure {
  const result: MovieFormFailure = { fields: {}, general: [] };
  if (!(error instanceof ApiRequestError) || !error.apiError) {
    result.general.push('The request could not be completed. Please try again.');
    return result;
  }
  if (error.status === 409 && error.apiError.code === 'MOVIE_SLUG_CONFLICT') {
    result.fields.slug = 'This slug is already used';
    if (error.apiError.requestId) result.general.push(`Request ID: ${error.apiError.requestId}`);
    return result;
  }
  for (const entry of error.apiError.fieldErrors ?? []) {
    if (knownField(entry.field, values)) {
      const message = /^people\[\d+\]\.(?:personId|name)$/.test(entry.field)
        && /more than one person|ambiguous|several people/i.test(entry.message)
        ? 'Several people have this name; pick one.' : entry.message;
      result.fields[entry.field] ??= message;
    } else {
      result.general.push(`${entry.field}: ${entry.message}`);
    }
  }
  if (Object.keys(result.fields).length === 0 && result.general.length === 0) result.general.push(error.apiError.message);
  if (error.apiError.requestId) result.general.push(`Request ID: ${error.apiError.requestId}`);
  return result;
}

function knownField(field: string, values?: Pick<MovieFormValues, 'genres' | 'people'>): boolean {
  if (['title', 'slug', 'description', 'releaseYear', 'maturityRating', 'genres', 'people', 'status'].includes(field)) return true;
  const genre = /^genres\[(0|[1-9]\d*)\]$/.exec(field);
  if (genre) return Number(genre[1]) < (values?.genres.length ?? 10);
  const person = /^people\[(0|[1-9]\d*)\](?:\.(?:personId|name|role))?$/.exec(field);
  return person !== null && Number(person[1]) < (values?.people.length ?? 50);
}

export function trimCatalogText(value: string): string {
  return value.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '');
}

function requiredText(value: string, label: string, maximum: number, multiline = false, wireValue = value): string | null {
  if ([...wireValue].length > maximum) return `${label} must be ${maximum} characters or fewer.`;
  // Unicode Cc, with only TAB/LF/CR allowed for the multiline description, as in the backend.
  const withoutAllowed = multiline ? value.replace(/[\t\r\n]/g, '') : value;
  if (/\p{Cc}/u.test(withoutAllowed)) return `${label} must not contain control characters.`;
  if (trimCatalogText(value) === '') return `${label} is required.`;
  return null;
}

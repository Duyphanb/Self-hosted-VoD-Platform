-- Rename in place so row IDs, foreign-key targets and grants follow the same relations.
ALTER TABLE movies RENAME TO titles;
ALTER TABLE movie_genres RENAME TO title_genres;
ALTER TABLE movie_credits RENAME TO title_credits;

-- The media, progress and history keys keep movie_id until the episode migration.
ALTER TABLE title_genres RENAME COLUMN movie_id TO title_id;
ALTER TABLE title_credits RENAME COLUMN movie_id TO title_id;
ALTER TABLE ratings RENAME COLUMN movie_id TO title_id;
ALTER TABLE watchlist_items RENAME COLUMN movie_id TO title_id;

-- PostgreSQL also renames the backing indexes of renamed primary and unique constraints.
ALTER TABLE titles RENAME CONSTRAINT movies_pkey TO titles_pkey;
ALTER TABLE titles RENAME CONSTRAINT movies_status_check TO titles_status_check;
ALTER TABLE titles RENAME CONSTRAINT ux_movies_slug TO ux_titles_slug;

ALTER TABLE title_genres RENAME CONSTRAINT movie_genres_pkey TO title_genres_pkey;
ALTER TABLE title_genres RENAME CONSTRAINT movie_genres_genre_id_fkey TO title_genres_genre_id_fkey;
ALTER TABLE title_genres RENAME CONSTRAINT movie_genres_movie_id_fkey TO title_genres_title_id_fkey;

ALTER TABLE title_credits RENAME CONSTRAINT movie_credits_pkey TO title_credits_pkey;
ALTER TABLE title_credits RENAME CONSTRAINT movie_credits_credit_role_check TO title_credits_credit_role_check;
ALTER TABLE title_credits RENAME CONSTRAINT movie_credits_movie_id_fkey TO title_credits_title_id_fkey;
ALTER TABLE title_credits RENAME CONSTRAINT movie_credits_person_id_fkey TO title_credits_person_id_fkey;

ALTER TABLE ratings RENAME CONSTRAINT ratings_movie_id_fkey TO ratings_title_id_fkey;
ALTER TABLE ratings RENAME CONSTRAINT ux_ratings_user_movie TO ux_ratings_user_title;
ALTER TABLE watchlist_items RENAME CONSTRAINT watchlist_items_movie_id_fkey TO watchlist_items_title_id_fkey;
ALTER TABLE watchlist_items RENAME CONSTRAINT ux_watchlist_items_user_movie TO ux_watchlist_items_user_title;

ALTER INDEX ix_movies_release_year RENAME TO ix_titles_release_year;
ALTER INDEX ix_movies_search_vector RENAME TO ix_titles_search_vector;
ALTER INDEX ix_movies_status RENAME TO ix_titles_status;
ALTER INDEX ix_movie_credits_person_id RENAME TO ix_title_credits_person_id;
ALTER INDEX ix_movie_credits_role RENAME TO ix_title_credits_role;
ALTER INDEX ix_ratings_movie_id RENAME TO ix_ratings_title_id;

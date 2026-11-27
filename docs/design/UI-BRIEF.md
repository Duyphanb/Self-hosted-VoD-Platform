# UI Design Brief (Yuumé)

Status: design brief for the first acceptance criterion of
[#200](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/200). It records the owner's visual and
interaction decisions of 2026-11-25 and 2026-11-26 for the shared UI and turns them into rules that the #200
implementation pull requests follow. It is a design document, not evidence that
anything is implemented. Behaviour contracts stay in the frozen requirements and architecture documents; where this
brief and a frozen document disagree, the frozen document wins until an approved change updates it.

## 1. Scope

In scope (shared UI, #200 phase 1 and the base for later screens):

- brand, colour tokens and contrast rules, typography, spacing, radius, elevation and motion;
- layout widths, breakpoints and density;
- navigation and the viewer, admin and guest shells;
- the Vietnamese string table and content-language rules;
- the component inventory and interaction rules;
- screen states of the shell, the access pages and the admin pages;
- accessibility rules and the evidence each UI pull request provides.

Out of scope here, designed elsewhere (planned decision records; links work once they are merged):

| Area | Owner document |
|---|---|
| Content model, title/episode routes, activation, Admin Preview route | ADR-013 (#244) |
| Search, suggestions, recommendations and the Home row order | ADR-015 (#128) |
| Player controls, menus, hotkeys, captions, skip button, end card | ADR-018 (#249) |
| Subtitle tracks, markers, seek previews, Marker Editor | ADR-019 (#247, #248) |
| Posters, backdrops, frames, image upload and delivery | ADR-020 (#199) |
| Collections, studio and creator pages, personal lists | ADR-021 |
| Provider data, including the worldwide trending charts | ADR-014 (#245, #246) |

Those screens use the tokens, components and rules of this brief; their layout, data and states belong to their own
documents and issues. The viewer pages (Home, browse, title page, Continue Watching) are #200's phase 2 and get their
own issue; sections 7 and 8 give only the layout and navigation rules they inherit.

## 2. Decisions recorded here

| Decision | Date | Source |
|---|---|---|
| Pink-and-black dark UI; cinematic and compact densities | 2026-11-25 | owner, whole-system plan review |
| Mixed density: density A on Home and title pages, density B on browse, search, library and admin; on the Watch page the episode list sits beside the player on wide screens and below it on narrow ones; a film has no list | 2026-11-26 | owner |
| Vietnamese UI everywhere, admin included, from one typed string table; display title first, original or romaji name as a smaller second line; audio and subtitle names in Vietnamese | 2026-11-26 | owner |
| Brand name "Yuumé"; the repository and technical identifiers keep their names | 2026-11-26 | owner |
| Navigation "Trang chủ · Phim lẻ · Phim bộ · Anime · Bộ sưu tập", with search and the account menu on the right | 2026-11-26 | owner |
| Reference screenshots are layout references only: no Netflix name, logo or red, no copied artwork | 2026-11-13 | owner |
| Desktop first; small screens keep basic, functional layouts but get no mobile redesign | 2026-11-13 | owner |

## 3. Brand

- **Name:** "Yuumé". Viewers never see the repository name "Self-hosted VoD Platform".
- **Wordmark:** text only, no symbol. "Yuumé" in the system font stack (section 5) at weight 800 with −0.03 em letter
  spacing, in `text`; the "é" may be set in `accent` (5.05:1 on the `surface` header, 5.57:1 on `bg`). The "é" is
  always the precomposed character U+00E9 (Unicode NFC) in source files and strings. The accessible name is the
  visible text; no `aria-label` override. The wordmark looks slightly different per operating system because it uses the system font; a fixed
  outline wordmark is a later option when a logo is wanted.
- **Where the name appears:** the header wordmark (a link to the signed-in landing page or, signed out, to `/`); the
  document title of every route as `Tên trang · Yuumé`; `<meta name="application-name">`; the guest landing and the
  sign-in and registration cards; an About page ("Giới thiệu") that also carries the third-party notices. The About
  page arrives with the first pull request that ships a third-party asset (the icon set, section 10).
- **Identifiers stay:** repository, package `vod-frontend`, Java packages, Compose services, buckets, the Web Lock
  name, storage keys and cookie names keep their current names (renaming them would sign everyone out). New external
  identifiers use the ASCII form `yuume`.
- **Not yet checked:** name collisions and trademarks; checked when the domain is bought (#250).

## 4. Colour tokens and contrast

### 4.1 Tokens

Values are defined once, as CSS custom properties holding RGB channels, and mapped into the Tailwind 3 theme
(`theme.extend.colors` with `<alpha-value>`), so components never hard-code a colour. Ratios below are WCAG 2.x
relative-luminance contrast ratios, computed for these exact values; a translucent colour is first composited over
its backdrop in sRGB and rounded to 8-bit channels, as a browser renders it.

| Token | Value | Use | Contrast |
|---|---|---|---|
| `bg` | `#0B0B10` | page background | — |
| `surface` | `#17171F` | panels, header, inputs | — |
| `raised` | `#221F29` | menus, hovered rows, dialogs | — |
| `text` | `#F8F5FA` | body and headings | 18.17 on `bg`, 16.49 on `surface`, 14.99 on `raised` |
| `text-muted` | `#A1A1AA` | secondary text, placeholders | 7.66 / 6.95 / 6.32 |
| `accent` | `#EC4899` | primary fills, current-state indicators, progress fill; text only for short emphasis | as text 5.57 / 5.05 / 4.59 |
| `on-accent` | `#0B0B10` | the only text and icon colour on `accent` and `accent-hover` | 5.57 on `accent`, 7.41 on `accent-hover` |
| `accent-hover` | `#F472B6` | hover fill of primary actions | 7.41 / 6.73 / 6.12 as text |
| `focus` | `#F472B6` | focus ring | 7.41 / 6.73 / 6.12 against the surface behind the ring |
| `action-solid` | `#BE185D` | secondary solid actions, fill only | `on-solid` 6.04 |
| `action-solid-hover` | `#9D174D` | hover of `action-solid` | `on-solid` 7.88 |
| `danger-solid` | `#B91C1C` | destructive buttons, fill only | `on-solid` 6.47 |
| `on-solid` | `#FFFFFF` | text and icons on `action-solid`, `action-solid-hover`, `danger-solid` | see the fills |
| `success` | `#22C55E` | ready state, text and icons | 8.62 / 7.82 / 7.11 |
| `warning` | `#FBBF24` | processing and attention, text and icons | 11.76 / 10.67 / 9.71 |
| `danger` | `#F87171` | errors, text and icons | 7.10 / 6.44 / 5.86 |
| `line` | `#303038` | decorative dividers only | 1.50 / 1.36 / 1.24 (never a required boundary) |
| `control-boundary` | `#777782` | borders of inputs, checkboxes, outlined buttons | 4.44 / 4.03 / 3.66 |
| `disabled-text` | `#8B8B94` | labels of disabled controls | 5.81 / 5.28 / 4.80 |
| `progress-track` | `#36363C` | unfilled part of progress and seek tracks | `accent` fill against it 3.40 |
| `track-outline` | `#83838E` | outline of tracks and sliders | 5.24 / 4.75 / 4.32; 3.20 against `progress-track` |
| `selected-bg` | `#3A1428` | selected rail item, current page in lists, current episode | `text` 14.83, `accent-hover` 6.05 |

### 4.2 Rules

- **Text:** body and label text meets 4.5:1 against its actual background; large text (24 px, or 18.66 px bold)
  meets 3:1. `accent` as text is used only on `bg`, `surface` or `raised`.
- **Fills:** `accent` carries only `on-accent` (white on `accent` is 3.53:1 and `text` on it 3.26:1, both too low).
  `action-solid`, `action-solid-hover` and `danger-solid` carry only `on-solid` and are never used as text colours
  (`action-solid` as text is 2.95:1 on `surface`).
- **Focus:** every focusable element shows a 2 px `focus` ring outside a 2 px gap in the colour of the surface behind
  it, so the ring never touches an `accent` fill (ring on `accent` would be 1.33:1). The ring is never removed without
  a visible replacement and is not clipped by `overflow: hidden` containers. Under `forced-colors: active` the ring
  uses the system `Highlight` colour.
- **Selection and state:** a selected or current item shows a non-colour indicator that the unselected item does
  not have (a check icon, an underline or a 2 px border that appears only when selected), with at least 3:1 against
  the adjacent colours, in addition to the `selected-bg` fill; it carries `aria-pressed`, `aria-current`,
  `aria-selected` or `aria-checked` as its pattern requires. A change of colour alone (fill or border hue) is not
  enough, and `selected-bg` alone is not a boundary (1.01:1 against `raised`). The selected state is checked in normal
  and forced colours. Statuses always carry a word ("Sẵn sàng", "Đang xử lý", "Lỗi").
- **Disabled:** disabled controls use a `raised` fill, a `line` border and `disabled-text`; text is never dimmed with
  opacity (45 % opacity puts a `text` label at 4.25:1 on `surface`). A disabled control stays readable and, where it
  hides an explanation, a nearby text says why.
- **Boundaries:** a boundary that identifies a control (input, checkbox, outlined button, slider track) uses
  `control-boundary` or `track-outline` (3:1 or more). `line` only separates content that is identifiable without it.
- **Text over images:** text sits on a solid panel or on a scrim of at least 85 % `bg` across the whole text block
  (measured against a pure white image pixel: `text` 12.16, `text-muted` 5.13, `accent-hover` 4.96). The image
  shows beside the text, not behind it. Hero and image surfaces are specified with their screens (ADR-020, phase 2).
- **Progress bars:** the fill is `accent` on `progress-track` with a `track-outline` outline; the value is always
  also given as text (time, percentage or "Xem tiếp từ 12:30").
- **Tokens are tested:** a unit test recomputes every declared token pair against its threshold (as
  `PosterArt.test.tsx` already does for the generated posters), so a changed value cannot silently fail contrast.
- **Generated posters (`PosterArt`):** the gradients move to the pink and black family and keep white text at 4.5:1
  or more along the whole gradient, which the existing test checks (start colours measured: pink-700 6.04, pink-800
  7.88, rose-700 6.29, fuchsia-700 6.32, fuchsia-800 8.24, purple-700 6.98, violet-700 7.10, zinc-600 7.73; the dark
  end colours are higher).

## 5. Typography

- **Font stack:** `system-ui, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", "Noto Sans JP",
  "Yu Gothic UI", Meiryo, sans-serif`. No remote or self-hosted web font in phase 1: no extra request, no licence to
  track, and the system fonts render Vietnamese and Japanese.
- **Sizes (CSS px):** body 15-16 with line-height 1.5; labels and metadata 13-14 (never smaller than 12); section
  headings 22-24; page titles 36-48; hero titles 44-60. Headings use line-height of at least 1.2 so that stacked
  Vietnamese diacritics (Ấ, Ổ, Ữ) on two-line titles do not touch.
- **Weights:** 400, 500, 600, 700; 800 only for the wordmark.
- **Numbers:** times and counts use `font-variant-numeric: tabular-nums` where they update in place.

## 6. Spacing, radius, elevation, motion

- **Spacing scale:** 4, 8, 12, 16, 24, 32, 48, 64 px.
- **Radius:** 8 px for controls, 12 px for panels and cards, 16 px for large media surfaces.
- **Elevation:** expressed by the surface step (`bg` → `surface` → `raised`) plus a 1 px border; no heavy shadows, no
  blur, no glow.
- **Motion:** only opacity and transform, 120-180 ms; no motion at all under `prefers-reduced-motion: reduce`. No
  element moves or advances by itself (no autoplaying carousels, no hover video, no autoplay trailers).

## 7. Layout and density

- **Widths:** content up to 1600 px with 32-64 px side gutters at desktop widths; the admin workspace is a 220 px
  rail plus content up to 1600 px.
- **Viewports:** designed and screenshot-checked at 1280x800, 1440x900 and 1920x1080; functional (no broken layout,
  no lost content or action) at 1024 px and at 320 CSS px wide. Text resized to 200 % at 1280 px and reflow at
  320 CSS px are checked separately (WCAG 1.4.4 and 1.4.10). At 320 px nothing scrolls horizontally except content
  that needs a two-dimensional layout (a video, a wide data table in its own scroll region); rows of cards wrap
  instead of scrolling sideways.
- **Narrow shells (below 1024 px):** the header wraps onto two lines: the wordmark, search and the account menu on the
  first, the navigation links on the second, wrapping rather than hiding behind a menu; the source and keyboard order
  follow the visual order. The admin rail becomes a wrapped row of links above the content;
  admin tables switch to the stacked row-as-card layout the admin list already uses below 768 px, so every action
  stays reachable without horizontal scrolling.
- **Density A ("cinematic"):** Home and title pages (and franchise pages). Six 2:3 posters per row at 1440 px. A hero
  surface is height-limited so that the first content row (on Home, "Đang xem") is visible at 1280x800.
- **Density B ("compact"):** browse, search, library and admin. Eight posters per row at 1440 px with a minimum card
  width; cards show the title once as text.
- **Watch page:** a 16:9 video area; for a title with several episodes the episode list sits in a 280-320 px panel
  beside the player from 1280 px wide, and below the player on narrower windows; a film has no list. The player and
  its controls follow ADR-018.
- **Admin:** density B, with server-paged semantic tables; no hero.

## 8. Navigation and shells

### 8.1 Viewer shell

- Header: wordmark, primary navigation, then search and the account menu on the right.
- **Target primary navigation:** Trang chủ · Phim lẻ · Phim bộ · Anime · Bộ sưu tập. "Phim lẻ" lists films, "Phim
  bộ" lists seasons, OVAs, ONAs and specials, "Anime" filters by the owner's "Anime" genre, and "Bộ sưu tập" appears
  once collections ship (ADR-021). The routes behind these items come with the content model (ADR-013) and the
  viewer phase 2; an item is shown only when its route exists.
- **Interim navigation** (until those routes exist): "Phim" (today's `/movies` list) and "Đang xem"
  (`/continue-watching`), with search added when it ships. "Đang xem" moves into the account menu once Home with its
  "Đang xem" row exists.
- **Account menu** (a menu button): Đang xem (only after it has left the primary navigation), Danh sách của tôi (once
  the watchlist ships, Sprint 8), Tài khoản, Quản trị (administrators only; visibility in the menu is presentation, the
  server still enforces the role), Đăng xuất.
- The current page's link carries `aria-current="page"` and a visible indicator (an `accent` underline, 5.05:1 on the
  `surface` header) besides the colour change.
- A "Bỏ qua đến nội dung" skip link is the first focusable element of the page, before the session banners of
  section 8.4, and moves focus to `main`. The first Tab stop is tested with the retry banner visible.

### 8.2 Admin shell

- Same header; below it a left rail with only the items that exist: "Nội dung" today; "Tải lên", "Metadata" and "Sao
  lưu" join when their features ship. The rail marks the current item with `aria-current`.
- Admin pages keep their URL filter state, their return-to-list navigation and their focus restoration.

### 8.3 Guest shell

- Wordmark plus "Đăng nhập" and "Đăng ký"; no catalogue content, artwork or metadata before sign-in.

### 8.4 Behaviour the shells must keep

- One authentication and form-exit-guard provider sits above all shells, so splitting the presentation never splits
  the session lifecycle. Sign-out stays guarded: a mounted form or upload can ask first, and only one sign-out runs at
  a time.
- The session notices that the authentication provider renders today above the shell (blocked site data, single-tab
  mode, unconfirmed sign-out or failed restore with its retry button) are restyled as banners and placed after the
  skip link, without changing their roles (`note`, `alert`) or their logic.
- **Fixes required by #200:** the upload page registers a form-exit guard so that "Đăng xuất" during an upload asks
  first (today only route changes and page unload are guarded), with the same phase policy as the existing leave
  dialog: while the file is being sent the admin may cancel the upload and sign out, or stay; while the movie is
  being checked or the file saved to storage the dialog only offers to stay until the phase settles. If the upload
  settles while the dialog is open, the dialog closes without signing out and focus moves to the upload outcome or
  status, as the route-leave dialog does; the admin can then sign out again. The dialog keeps the warning that
  cancelling does not roll back server work. The admin status filter shows its selected button visibly (today both
  states share one style; section 4.2).
- Every route sets the document title ("Danh sách phim · Yuumé"). On a route change focus moves to the page heading
  or the page region; the list, detail and watch pages do this today, and the shells make it shared behaviour for the
  pages that do not yet (guest, access and not-found pages).

## 9. Strings and language

- **One typed string table:** `src/strings/vi.ts`, grouped by feature, holding constants and parameterised functions
  (for example `resumeAt(position)`); no i18n library. A second language later becomes a second module of the same
  type chosen at start-up. The table covers app-authored visible text and accessible names. Labels the browser draws
  itself (the native video controls until ADR-018 replaces them, the file picker, the page-unload prompt) follow the
  browser's language and are outside the table.
- **Document language:** `<html lang="vi">`; text in another language carries its own `lang` (for example a Japanese
  original title gets `lang="ja"`).
- **Formatting:** numbers, dates and durations use `Intl` with `vi-VN`; no raw ISO timestamps on screen.
- **Server errors:** known API error codes map to Vietnamese messages from the table; field errors (which carry a
  field name and a message, no machine-readable reason) get a Vietnamese message per field, with a generic
  field-level fallback. The request ID is shown when the response supplies one (it is optional and nullable). The
  server's own English text appears only for codes the table explicitly allows, as a secondary detail marked
  `lang="en"`; unknown codes get a generic Vietnamese message. Viewer screens never show encoding failure codes or
  messages (ADR-011 D18).
- **Tests:** tests import their expected text from the table instead of repeating literals, so a wording change does
  not rewrite tests.
- **Content names:** the display title comes first; when the owner or a provider supplied an original or romaji name
  it appears as a smaller second line. Audio and subtitle names are Vietnamese ("Tiếng Nhật", "Phụ đề tiếng Việt").
- **Source text:** all strings are Unicode NFC.

Starter glossary (the table may refine wording; the meaning stays):

| Concept | UI text |
|---|---|
| Home, films, series, collections, search | Trang chủ, Phim lẻ, Phim bộ, Bộ sưu tập, Tìm kiếm |
| Continue watching, my list, account, administration | Đang xem, Danh sách của tôi, Tài khoản, Quản trị |
| Sign in, register, sign out, signing out | Đăng nhập, Đăng ký, Đăng xuất, Đang đăng xuất… |
| Watch, resume, details | Xem, Xem tiếp, Chi tiết |
| Ready, processing, not available, no episodes yet | Sẵn sàng, Đang xử lý, Không khả dụng, Chưa có tập |
| Draft, published, archived (admin only) | Bản nháp, Đã xuất bản, Đã lưu trữ |
| Retry, loading, nothing here yet | Thử lại, Đang tải…, Chưa có nội dung |
| Skip to content | Bỏ qua đến nội dung |

## 10. Components

### 10.1 Inventory

| Component | Status | Notes |
|---|---|---|
| `LoadingState`, `EmptyState`, `ErrorState` | existing, restyled | keep their semantics and heading levels; `ErrorState` follows section 9 (mapped Vietnamese message, request ID only when supplied, English detail only for allowlisted codes) and is tested with an unknown code and with an absent or null request ID |
| `Pagination` | existing, restyled | behaviour and tests unchanged |
| `AuthFormCard` and the auth pages | existing, restyled | no flow change |
| `MovieCard` (later the title card) | changed | one link; 2:3 artwork with the generated poster as fallback (ADR-020 owns real artwork); status line in words |
| `ContinueWatchingCard` | changed | progress bar per section 4.2; position as text |
| `PosterArt` | changed | pink and black gradients (section 4.2) |
| `MovieStatusBadges` → `StatusBadge` | changed | word plus colour; catalogue status only in admin views |
| `buttonStyles.ts` → `Button` and `LinkButton` | replaced | variants primary, secondary, danger, ghost; loading and disabled states; the old helpers are removed only after every import moves |
| `useModalFocus` and the three dialogs → `Dialog` | merged | one dialog for archive, unsaved changes and upload exit (later also activation); focus moves in, Tab stays inside, Escape closes where safe, and on close focus goes to the target each caller provides, keeping today's destinations (the archive dialog focuses the list heading after a successful archive and the opener otherwise; the upload leave dialog returns to the upload status or outcome); existing focus assertions stay |
| `AppLayout` → `ViewerShell`, `AdminShell`, `GuestShell` | split | section 8 |
| `Field`, `TextInput`, `TextArea`, `Select`, `Checkbox` | new | visible label, help text and error wired with `aria-describedby` and `aria-invalid`, as the current forms do |
| `StatusNotice` | new | the inline `role="status"` notice already used after saves; no timed toasts |
| `Banner` | new | session notices, later maintenance or backup warnings |
| `Icon` | new | about 20 SVG icons in a local module derived from Lucide (ISC licence, listed on the About page); decorative icons are `aria-hidden`, icon-only buttons have a Vietnamese accessible name |
| `SectionHeader`, `Skeleton`, `ProgressBar` | new | skeletons keep the final layout dimensions |
| `Rail` | new | horizontal row with a heading and scroll buttons; used by later screens |
| Data table framework | not used | semantic `<table>` with caption and server pagination is enough |

### 10.2 Interaction rules

- Links navigate; buttons act. No interactive element inside another (a card is one link; extra actions are siblings).
- Every control works with the keyboard alone, in a logical order, with the focus ring of section 4.2.
- Menus follow the menu-button pattern (`aria-haspopup`, `aria-expanded`, arrow keys, Escape returns focus); player
  menus are specified in ADR-018.
- Rails scroll by buttons and keyboard; focused cards scroll into view; nothing auto-advances.
- Target size: at least 24x24 CSS px for every pointer target (WCAG 2.5.8); normal buttons and inputs are 36-44 px
  tall.
- Status changes that do not move focus are announced through one polite `role="status"` region; errors use
  `role="alert"`.
- No hover-only information or actions; tooltips also appear on focus.
- Reordering by dragging always has a button alternative ("Lên", "Xuống"; WCAG 2.5.7), for example in the collections
  editor of ADR-021.

## 11. Screen states (shell, access pages, admin)

Copy changes to Vietnamese; the behaviour of every state below is unchanged unless a #200 fix (section 8.4) says
otherwise.

| Screen | States to design and test |
|---|---|
| Shell | initialising (no navigation flash), signed out, signed in, signed in as administrator, signing out (button disabled, "Đang đăng xuất…"), unconfirmed sign-out or failed restore (alert with retry), blocked site data (note), single-tab mode (note) |
| Guest landing, sign-in, registration | idle, submitting, field errors, server error (section 9), success redirect, the unconfirmed sign-out banner |
| Not found, forbidden | heading as the page title, one way back, no private data |
| Account | as today |
| Admin list | loading skeleton, list, empty catalogue, no match for the filter (with "Hiện tất cả"), page past the end (with "Về trang cuối"), error with retry, placeholder data while paging (`aria-busy` and a loading indicator instead of dimming the rows, which would put `text-muted` at 3.14:1; Archive disabled and the View, Edit and Upload links unchanged, as today), archive dialog, success notice after save or archive, selected status filter visible |
| Admin form (create, edit) | loading, not found, forbidden, field errors, dirty-form guard on navigation and sign-out, saving (guard says the save is in progress), conflict (slug already used), success back to the same filtered list |
| Admin upload | the frozen #175 states unchanged: idle, checking, sending with progress and cancel, saving to storage (cancel hidden), accepted, unknown outcome (no automatic resend), confirmed not accepted (nothing committed; manual retry), cancelled, already in progress (409), too large (413), rejected file (400), signed out, forbidden, not found; exit guard on navigation, page unload and, new, sign-out, each tested in the sending, checking and saving phases and with a phase that settles while the dialog is open |

The viewer phase 2 screens inherit sections 4-10; their states are specified in the phase 2 issue with ADR-013,
ADR-015, ADR-020 and ADR-021. Player states belong to ADR-018 and ADR-019.

## 12. Accessibility

The UI targets **WCAG 2.2 Level AA** in the verified desktop browsers
([NFR-017](../requirements/NFR.md#requirements)). Besides the contrast and
focus rules of section 4, each screen meets in particular:

- 1.3.1 and 4.1.2: native elements first; names, roles and states exposed (pressed, current, expanded, invalid);
- 1.4.1: no information by colour alone; 1.4.4: text resizes to 200 % without loss; 1.4.10: content reflows at
  320 CSS px without horizontal scrolling, except content that needs a two-dimensional layout;
- 1.4.11: control boundaries and state indicators at 3:1;
- 2.1.1 and 2.1.2: everything operable by keyboard, no trap; 2.4.1: the skip link; 2.4.2: a title per route;
- 2.4.3 and 2.4.7: logical focus order and a visible focus; 2.4.11: focus not hidden by the sticky header
  (`scroll-padding-top`);
- 2.5.7 and 2.5.8: drag alternatives and 24 px targets;
- 3.1.1 and 3.1.2: page and part language;
- 1.4.12: no loss when text spacing is increased; 1.4.13: content shown on hover or focus (tooltips) is dismissible,
  hoverable and persistent;
- 3.2.3: repeated navigation keeps the same relative order on every page (guest and signed-in navigation may differ);
- 3.3.1 and 3.3.3: errors identified in text with a suggestion; 3.3.7 and 3.3.8: no re-entry of known data and no
  memory or puzzle test in sign-in (paste allowed in the password field);
- 4.1.3: status messages announced without moving focus.

This is a design target, not a conformance claim. A claim may only name tested full pages and complete processes (for
example the whole upload flow), never a single component.

## 13. Evidence each UI pull request provides

| Check | How | Pass |
|---|---|---|
| Token contrast | Vitest test over the token definitions | every declared pair meets its threshold |
| Automated accessibility scan | axe rules tagged for WCAG 2.0, 2.1 and 2.2 Level A and AA run in the Playwright suite (Chromium and Firefox) on each changed screen state; the harness, which today renders only session and player fixtures, is extended to render the real app routes against fixture API responses; the scanner (axe-core, MPL-2.0) is a development-only dependency, never shipped | every reported A or AA violation is fixed, whatever axe's impact rating; the target-size rule enabled. A clean scan is not a WCAG result: the manual rows below cover what automation cannot |
| Keyboard | Playwright keyboard-only walk-through of each changed screen | every action reachable; logical order; focus visible and not hidden |
| Reduced motion and forced colours | Playwright contexts with `reducedMotion: 'reduce'` and `forcedColors: 'active'` | no running animation; focus, selection and disabled states stay visible |
| Text resize and reflow | text at 200 % at 1280 px; the page at 320 CSS px wide | no lost content or action; at 320 px no horizontal scrolling except two-dimensional content (section 7) |
| Manual criteria | every applicable WCAG 2.2 A and AA criterion that automation cannot decide, checked on each changed screen: at least focus order and headings, text spacing (1.4.12), content on hover or focus (1.4.13), error identification and suggestions, consistent navigation, language of parts, status announcements | the result and its scope (screens, browsers) recorded in the pull request |
| Screenshots for review | 1280x800, 1440x900, 1920x1080 from fixtures only (no real titles, accounts or personal data) | reviewed by the owner; never updated blindly |
| Behaviour | existing Vitest, Playwright and real-Chrome smoke suites | green; selectors and interactions change only where the visible text or the widget changed (for example "Sign out" moving into the account menu), and the session, upload and playback assertions stay as they are |
| Screen reader | manual NVDA row in Chrome or Edge and a Firefox row for the shell, dialogs and the admin table | announcements and focus as specified |
| Bundle | build output size before and after | the change and its cause stated in the pull request |

Image, page-load and player measurements are defined with their screens (ADR-020, the phase 2 issue, ADR-018).

## 14. Delivery

Issue #200 is implemented in three pull requests after this brief is approved:

1. tokens, primitives and shells (including the skip link and the per-route titles), with today's English text;
2. the Vietnamese string table (`lang="vi"`, error-code messages, tests reading the table);
3. the admin pages on the primitives, with the two fixes of section 8.4.

Issue #121 (development proxy and build context) stays separate. Changes to this brief go through a pull request
that names the owner decision behind them.

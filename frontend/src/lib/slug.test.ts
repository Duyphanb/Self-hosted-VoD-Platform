import { describe, expect, it } from 'vitest';
import { slugify } from './slug';

// The same cases as the backend's SlugNormalizerTest, so the client and the API agree on every slug.
describe('slugify', () => {
  it.each([
    ['Đất Rừng Phương Nam', 'dat-rung-phuong-nam'],
    ['Hành Động', 'hanh-dong'],
    ['Khoa học viễn tưởng', 'khoa-hoc-vien-tuong'],
    ['Tâm Lý - Tình Cảm', 'tam-ly-tinh-cam'],
    ['Nguyễn Thị Ánh Viên', 'nguyen-thi-anh-vien'],
    ['Trấn Thành', 'tran-thanh'],
    ['ĐÀ LẠT', 'da-lat'],
    ['đđ ĐĐ', 'dd-dd'],
    ['Sci-Fi & Fantasy', 'sci-fi-fantasy'],
    ['Top 10 Phim 2024', 'top-10-phim-2024'],
    ['  --Hài__Hước!!  ', 'hai-huoc'],
    ['Amélie 東京 Ωmega', 'amelie-mega'],
    ['Straße', 'stra-e'],
    ['İstanbul', 'istanbul'],
    ['hanh-dong', 'hanh-dong'],
    ['a\u0000b\tc', 'a-b-c'],
    ['phim😀hay', 'phim-hay']
  ])('turns %j into %j', (value, slug) => {
    expect(slugify(value)).toBe(slug);
  });

  it('maps case, spacing and diacritic variants to one slug', () => {
    expect(slugify('Hài')).toBe('hai');
    expect(slugify('Hai')).toBe('hai');
    expect(slugify('  HÀI  ')).toBe('hai');
    expect(slugify('Hành   động')).toBe(slugify('hành động'));
  });

  it('gives precomposed and decomposed text the same slug', () => {
    expect(slugify('Phương Nam'.normalize('NFC'))).toBe('phuong-nam');
    expect(slugify('Phương Nam'.normalize('NFD'))).toBe('phuong-nam');
  });

  it('is idempotent', () => {
    const slug = slugify('Đất Rừng Phương Nam (2023)');
    expect(slug).toBe('dat-rung-phuong-nam-2023');
    expect(slugify(slug)).toBe(slug);
  });

  it.each(['', '   ', '---', '!!!', '東京', 'Драма', '😀', '\u0000\u001F', '̣́', '#', '-'])(
    'returns an empty slug for %j',
    (value) => {
      expect(slugify(value)).toBe('');
    }
  );
});

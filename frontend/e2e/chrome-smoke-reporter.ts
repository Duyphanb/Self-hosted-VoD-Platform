import type { Reporter } from '@playwright/test/reporter';

// Test output is produced only by the smoke's fixed checkpoints and allowlisted observations.
// Playwright errors, step arguments, raw requests and attachments are deliberately excluded.
export default class ChromeSmokeReporter implements Reporter {
  printsToStdio(): boolean {
    return true;
  }

  onStdOut(chunk: string | Buffer): void {
    for (const line of chunk.toString().split('\n')) {
      if (line.startsWith('CHROME_SMOKE ')) {
        process.stdout.write(line + '\n');
      }
    }
  }
}

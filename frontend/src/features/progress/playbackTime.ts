const SECONDS_PER_MINUTE = 60;
const MINUTES_PER_HOUR = 60;
const SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;

/** A playback position as a player's clock shows it: `0:45`, `12:34`, or `1:02:03` from one hour on. */
export function formatPlaybackPosition(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds));
  const hours = Math.floor(seconds / SECONDS_PER_HOUR);
  const minutes = Math.floor((seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE);
  const paddedSeconds = String(seconds % SECONDS_PER_MINUTE).padStart(2, '0');
  if (hours === 0) {
    return `${minutes}:${paddedSeconds}`;
  }
  const paddedMinutes = String(minutes).padStart(2, '0');
  return `${hours}:${paddedMinutes}:${paddedSeconds}`;
}

/** The time left, rounded up to whole minutes: `1 min left`, `45 min left`, `2 h left`, `1 h 5 min left`. */
export function formatTimeLeft(remainingSeconds: number): string {
  const totalMinutes = Math.ceil(Math.max(0, remainingSeconds) / SECONDS_PER_MINUTE);
  const hours = Math.floor(totalMinutes / MINUTES_PER_HOUR);
  const minutes = totalMinutes % MINUTES_PER_HOUR;
  if (hours === 0) {
    return `${minutes} min left`;
  }
  if (minutes === 0) {
    return `${hours} h left`;
  }
  return `${hours} h ${minutes} min left`;
}

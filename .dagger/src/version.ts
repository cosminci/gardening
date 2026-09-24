/** Derives the image version from `git describe --tags --always --dirty` output.
 *
 * On an exact tag `vX.Y.Z` this yields `X.Y.Z`; otherwise the describe form
 * `X.Y.Z-<n>-g<sha>` (or a bare short sha when no tag exists), with any leading `v` stripped. A
 * dirty tree keeps git's `-dirty` suffix so an accidental release build stays visible.
 */
export function deriveVersion(gitDescribe: string): string {
  const trimmed = gitDescribe.trim();
  if (trimmed === "") return "0.0.0-unknown";
  return trimmed.startsWith("v") ? trimmed.slice(1) : trimmed;
}

export function releaseVersion(now: Date): string {
  const day = [now.getUTCFullYear(), now.getUTCMonth() + 1, now.getUTCDate()].join(".");
  const hour = String(now.getUTCHours()).padStart(2, "0");
  const minute = String(now.getUTCMinutes()).padStart(2, "0");
  const second = String(now.getUTCSeconds()).padStart(2, "0");
  return `${day}-T${hour}${minute}${second}`;
}

export function assertReleaseVersion(tag: string): void {
  if (!/^\d{4}\.(?:[1-9]|1[0-2])\.(?:[1-9]|[12]\d|3[01])-T\d{6}$/.test(tag)) {
    throw new Error(`invalid release version: ${tag}`);
  }
}

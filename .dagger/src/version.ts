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

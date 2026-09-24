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

const STABLE_VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const RELEASE_TAG = /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-rc\.([1-9]\d*))?$/;

export function isStableReleaseVersion(version: string): boolean {
  return STABLE_VERSION.test(version);
}

export interface PublicationPlan {
  readonly version: string;
  readonly updateLatest: boolean;
}

export interface ReleaseSource {
  readonly tag: string;
  readonly clean: boolean;
  readonly tagType: string;
  readonly headRevision: string;
  readonly tagRevision: string;
  readonly mainAncestor: string;
}

export function assertReleaseSource(source: ReleaseSource): void {
  const stable = planPublication(source.tag, []).updateLatest;
  if (!source.clean) throw new Error("release refused: working tree is dirty");
  if (source.tagType !== "tag") throw new Error("release refused: expected an annotated tag");
  if (source.headRevision !== source.tagRevision)
    throw new Error("release refused: tag is not on HEAD");
  if (stable && source.mainAncestor !== source.headRevision) {
    throw new Error("release refused: stable tag is not merged to main");
  }
}

export function planPublication(tag: string, existingTags: readonly string[]): PublicationPlan {
  if (!RELEASE_TAG.test(tag)) throw new Error(`invalid release tag: ${tag}`);
  const version = tag.slice(1);
  if (existingTags.includes(version)) throw new Error(`version already published: ${version}`);
  const stableVersions = existingTags.filter((existing) => STABLE_VERSION.test(existing));
  const updateLatest =
    STABLE_VERSION.test(version) &&
    stableVersions.every((existing) => compareStableVersions(version, existing) > 0);
  return { version, updateLatest };
}

export function planLatestRepair(tag: string, existingTags: readonly string[]): string {
  const selected = planPublication(tag, []);
  if (!selected.updateLatest) throw new Error("latest requires a stable release");
  if (!existingTags.includes(selected.version))
    throw new Error(`version not published: ${selected.version}`);
  const otherTags = existingTags.filter((existing) => existing !== selected.version);
  if (!planPublication(tag, otherTags).updateLatest) {
    throw new Error("a newer stable release already exists");
  }
  return selected.version;
}

export function compareStableVersions(candidate: string, existing: string): number {
  return candidate.localeCompare(existing, "en", { numeric: true });
}

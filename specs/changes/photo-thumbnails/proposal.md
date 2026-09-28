# Photo thumbnails

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-28

**Grounded in:** A disposable JVM prototype run against the JDK version this backend already targets, against the real sample photos already in local storage. It found the JDK's built-in image support decodes and encodes JPEG and PNG but has neither a reader nor a writer for WebP, and that a bounded downscale-then-JPEG-quality search reliably lands real originals at or under a 100KB target, while a byte target isn't controllable through PNG or WebP output. This app's uploads are in practice a phone camera or one specific mirrorless body, never WebP, so the WebP gap is resolved by dropping WebP rather than adding a dependency for it.

Add a derived thumbnail to every photo, backfilled once for photos that predate it; drop WebP as an accepted upload format.

## What & Why

Photos are 3-10MB each, and the photo grid loads every visible photo at full size — on a mobile connection, a page of thumbnails costs tens of megabytes before anything is even opened. Each photo now also gets a small derived thumbnail, created together with its original; existing photos gain one through a one-time procedure that runs once, outside any request path. Accepted uploads narrow from three formats to two — JPEG and PNG only — since every real upload comes from a phone camera or one specific mirrorless body, neither of which produces WebP.

## Domain / Design Notes

```scala
enum PhotoMediaType:
  case Jpeg, Png

trait PhotoContentStore:
  def put(photo: PhotoId, original: PhotoContent, thumbnail: PhotoContent): PhotoWriteResult
  def get(photo: PhotoId): PhotoReadResult             // original — unchanged
  def getThumbnail(photo: PhotoId): PhotoReadResult    // new
  def delete(photo: PhotoId): PhotoWriteResult         // unchanged signature, now removes both

trait PlantManager:
  def getPhotoContent(photo: PhotoId): PhotoReadResult          // original — unchanged
  def getThumbnailContent(photo: PhotoId): PhotoReadResult      // new
  // addPhoto, removePhoto, getPhotos otherwise unchanged
```

- A thumbnail is derived from its original at upload time by downscaling to a bounded longest edge and re-encoding as JPEG, regardless of the original's media type — see Acceptance Criteria for the size target and its boundary case. Derivation is a pure, in-memory step with no side effects of its own; it happens before either variant is written.
- `put` gains a required second content parameter for the thumbnail and writes both together in one call. `get`/`getPhotoContent` keep returning exactly what they return today (the original); a new `getThumbnail`/`getThumbnailContent` sits alongside them for the derived copy, so every caller states which one it wants by which method it calls, not by an extra argument. `delete`'s signature is unchanged; it now removes both stored blobs.
- The one-time backfill enumerates existing photos through the existing plant/photo listing operations (across active and archived plants) and, for each one missing a thumbnail, reads its original through the existing `get` and writes it back through the same `put` upload uses, now paired with a derived thumbnail. It calls nothing beyond `get`/`put`, which the feature needs anyway — no method is added or changed just for the backfill.

## Alternatives Considered

- A three-step saga (write original, write thumbnail, write metadata, each independently compensated): rejected — thumbnail derivation is pure and happens before any write, and both blobs are written through one existing port call, so the pre-existing two-step compensation already covers it without new orchestration.
- Keeping WebP and adding a decode dependency for it: rejected — this is an in-house app; real uploads are phone photos (99%) or one specific mirrorless body's output (1%), never WebP, so there's nothing to decode in practice.
- A fixed resolution/quality setting instead of a size target: rejected — spiked originals ranged from ~5.7KB to ~343KB, so any one fixed setting either overshoots the target on large photos or wastes size on small ones.
- Thumbnails kept in the original media type: rejected — the JDK's PNG writer has only a resolution axis, no lossy/quality dial, so only JPEG output makes a byte target controllable.

## Invariants

- A photo's original bytes and media type are unchanged by this feature.

## Tradeoffs Accepted

- A thumbnail derived from a transparent PNG loses that transparency (rendered against an opaque background), since every thumbnail is JPEG-encoded to keep the size target controllable. Borne only by the thumbnail — the original PNG and its transparency are untouched.

## Acceptance Criteria

- A successful JPEG or PNG upload produces both an original and a thumbnail; there is never an observable state with one but not the other. A WebP upload is rejected as an unsupported media type, the same as any other unrecognized format.
- A thumbnail is at or under 100KB, except when even the smallest/lowest-quality derivation attempt still exceeds it, in which case that closest attempt is what's stored.
- If a thumbnail cannot be derived from an uploaded original, the upload fails as a whole and no content is persisted.
- Removing a photo removes its thumbnail along with its original; neither is reachable afterward.
- The photo grid renders thumbnails; a photo's full original is fetched only once that photo is activated, never before.
- Running the one-time backfill once gives every existing photo missing a thumbnail one, using the same derivation as upload; running it again afterward changes nothing. A photo whose original can't be processed (including one already stored in a format no longer accepted for upload) is skipped and reported, without stopping the rest of the run.

## Doc Sync

- `specs/design.md` — Domain model: revise "a photo's identity and content never change after upload" to also state that each photo carries an immutable derived thumbnail, created together with the original and removed together with it.

## Out of Scope

- Serving a thumbnail for a photo that hasn't been backfilled yet, or that the backfill couldn't derive one for — such a photo's thumbnail stays unavailable in the grid until resolved separately.

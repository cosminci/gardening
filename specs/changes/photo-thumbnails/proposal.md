# Photo thumbnails

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-28

**Grounded in:** A disposable JVM prototype against the real sample photos already in local storage found that downscaling to a bounded size and re-encoding at a searched JPEG quality reliably lands real originals at or under a 100KB target. A second prototype, the in-flight-write record and its recovery against a real SQLite database, confirmed all three crash points (before content lands, after content lands, after normal completion) are distinguishable and recoverable at the next startup — and that running the same reconciliation while the process is still live would need an untested age floor to avoid racing a request that's merely slow, not crashed, which is why recovery runs at startup only.

Add a derived thumbnail to every photo, backfilled once for photos that predate it; drop WebP as an accepted upload format.

## What & Why

Photos are 3-10MB each, and the photo grid loads every visible photo at full size — on a mobile connection, a page of thumbnails costs tens of megabytes before anything is even opened. Each photo now also gets a small derived thumbnail, created together with its original; existing photos gain one through a one-time procedure that runs once, outside any request path. Accepted uploads narrow to JPEG and PNG; WebP is dropped.

Adding a second blob to the same write also closes a gap in how that write survives a crash. Today, if the metadata write fails after content is written, in-process compensation deletes the content — but only if the process is still alive to run it; a crash between the two leaves orphaned content with no code left to clean it up. A durable record of the write's progress, reconciled once at the next startup, closes that gap without touching the process-alive case, which the existing in-process compensation already covers unchanged.

## Domain / Design Notes

```scala
enum PhotoMediaType:
  case Jpeg, Png

enum PhotoVariant:
  case Original, Thumbnail

trait PhotoContentStore:
  def put(photo: PhotoId, original: PhotoContent, thumbnail: PhotoContent): PhotoWriteResult
  def get(photo: PhotoId, variant: PhotoVariant): PhotoReadResult
  def delete(photo: PhotoId): PhotoWriteResult

trait PlantManager:
  def addPhoto(plant: PlantId, content: PhotoContent, idempotencyKey: String): AddPhotoResult
  def getPhotoContent(photo: PhotoId, variant: PhotoVariant): PhotoReadResult
  // removePhoto, getPhotos unchanged
```

- A thumbnail is derived from its original at upload time by downscaling to a bounded longest edge and re-encoding as JPEG, regardless of the original's media type — see Acceptance Criteria for the size target and its boundary case. Derivation is a pure, in-memory step with no side effects of its own; it happens before either blob is written.
- `put` gains a required second content parameter for the thumbnail and writes both together in one call, since a photo isn't complete with only one of them. `get`/`getPhotoContent` gain a `PhotoVariant` parameter: fetching either is the same operation — a stored blob for this photo — differing only in which one. `delete`'s signature is unchanged; it now removes both stored blobs.
- The one-time backfill enumerates existing photos through the existing plant/photo listing operations (across active and archived plants) and, for each one missing a thumbnail, reads its original through the existing `get` and writes it back through the same `put` upload uses, now paired with a derived thumbnail. It calls nothing beyond `get`/`put`, which the feature needs anyway — no method is added or changed just for the backfill.
- `PhotoId` remains entirely backend-assigned, exactly as before — `addPhoto` gains an `idempotencyKey` instead, a caller-supplied opaque token used only to recognize a retried request. It never becomes part of a photo's identity and is never returned or exposed as one.
- Before either blob is written, the journal database durably records an in-flight write against that `idempotencyKey` (not against a `PhotoId`, which doesn't exist yet), in its own transaction ahead of any content write. That record exists on disk before anything risky happens, so it survives a process crash the in-process compensation below could not. Once the backend assigns the `PhotoId` and writes content, that id is recorded against the same in-flight entry.
- The existing in-process compensation is unchanged and still runs first for a live process: it already deletes stray content immediately on a failed metadata write, without ever consulting the durable record. That record is purely a backstop for the one case compensation can't cover — the process dying before it gets the chance to run.
- Recovery reconciles every in-flight record exactly once, at the next process start — never while already running, since a live process's failures are already handled above, and nothing new can go stale while nothing is running. For each record with no matching completed photo, it checks whether the content write actually landed. If both blobs are fully present, it finishes the operation by writing the metadata and marking the record done — no bytes need to be resent. If they're not, it discards whatever partial content exists and drops the record; nothing was ever visible to `getPhotos`, so this is indistinguishable from the upload never having been attempted, and a retry with the same idempotency key starts clean.
- The idempotency key is ephemeral, unlike everything else here: it's retained only long enough to cover a crash-and-recovery window, then discarded. `PhotoId` and the photo it names are permanent domain state; the key is a request-deduplication detail that outlives neither the operation it covers nor a bounded retention window.
- `removePhoto` doesn't need one of its own: it already takes an existing `PhotoId` the caller learned from a prior successful list or upload, so retrying a removal is naturally idempotent by that identity — a repeat call finds nothing left to remove. It still gets the same in-flight-record-before-the-risky-write treatment, reconciled the same way at the next startup.
- Photo content continues to live on the NAS array, separate from the journal database, per the existing plant-photos design: this change adds a small durable record to the journal, it does not move photo bytes into it. Photo volume still never grows the journal database or its backup path.

## Alternatives Considered

- A three-step saga (write original, write thumbnail, write metadata, each independently compensated): rejected — thumbnail derivation is pure and happens before any write, and both blobs are written through one existing port call, so this stays a two-write operation regardless of orchestration.
- Moving photo content into the journal database as blobs, so the whole write is one database transaction with no separate durability mechanism needed: rejected — production keeps photo content on the NAS array specifically so photo volume never grows the appdata backup path (see the plant-photos change this one extends); moving bytes into the journal database reverses that, growing the backed-up file by the entire photo corpus.
- A dedicated durable workflow/saga engine (e.g. a self-hosted Temporal) or a hosted backend platform (e.g. Supabase) for cross-resource durability: rejected as disproportionate for a single-process, single-household app — the former's only supportable production persistence is a full multi-service cluster plus its own separate database, and the latter's storage and database are separate systems with the identical crash gap this change closes, just on heavier infrastructure.
- Reconciling the durable record periodically while the process is running, in addition to at startup: rejected — a live process's failures are already handled immediately by the existing in-process compensation, so a live sweep would only ever find a request that's still legitimately in progress (needing an age floor to avoid racing it) or a bug; the only case the durable record exists for is a crash, which is only discoverable once the process starts again anyway.
- A fixed resolution/quality setting instead of a size target: rejected — spiked originals ranged from ~5.7KB to ~343KB, so any one fixed setting either overshoots the target on large photos or wastes size on small ones.
- Thumbnails kept in the original media type: rejected — PNG is lossless, so it can't be reliably squeezed to a byte target for photographic content the way a lossy codec can; JPEG output is what makes the target achievable.

## Invariants

- A photo's original bytes and media type are unchanged by this feature.
- A crash or restart at any point during an upload or removal never leaves an observable inconsistency: a photo is either not listed at all (safe to retry) or fully present with both original and thumbnail — never partial, and never duplicated by a retry carrying the same idempotency key.

## Tradeoffs Accepted

- A thumbnail derived from a transparent PNG loses that transparency (rendered against an opaque background), since every thumbnail is JPEG-encoded to keep the size target controllable. Borne only by the thumbnail — the original PNG and its transparency are untouched.
- Recovery from a crash mid-write is eventual, not instant: a photo interrupted between its content write and its metadata write isn't finished or reclaimed until the process next starts, not immediately after the crash. Borne as a delay in reclaiming disk space or completing a stalled upload, on a NAS process that already restarts periodically.

## Acceptance Criteria

- A successful JPEG or PNG upload produces both an original and a thumbnail; there is never an observable state with one but not the other. A WebP upload is rejected as an unsupported media type, the same as any other unrecognized format.
- A thumbnail is at or under 100KB, except when even the smallest/lowest-quality derivation attempt still exceeds it, in which case that closest attempt is what's stored.
- If a thumbnail cannot be derived from an uploaded original, the upload fails as a whole and no content is persisted.
- Removing a photo removes its thumbnail along with its original; neither is reachable afterward.
- The photo grid renders thumbnails; a photo's full original is fetched only once that photo is activated, never before.
- Running the one-time backfill once gives every existing photo missing a thumbnail one, using the same derivation as upload; running it again afterward changes nothing. A photo whose original can't be processed (including one already stored in a format no longer accepted for upload) is skipped and reported, without stopping the rest of the run.
- An interrupted upload or removal (process crash, container restart) resolves on its own without operator intervention: if the content had already been durably written, the operation completes automatically the next time the process starts; otherwise nothing is persisted, and a retried upload carrying the same idempotency key is safe and creates nothing extra.

## Doc Sync

- `specs/design.md` — Domain model: revise "a photo's identity and content never change after upload" to also state that each photo carries an immutable derived thumbnail, created together with the original and removed together with it.

## Out of Scope

- Serving a thumbnail for a photo that hasn't been backfilled yet, or that the backfill couldn't derive one for — such a photo's thumbnail stays unavailable in the grid until resolved separately.

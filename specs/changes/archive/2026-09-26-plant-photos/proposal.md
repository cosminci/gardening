# Plant photos

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-25

Add per-plant dated photos: upload, remove, paginated view.

## What & Why

No visual history of a plant exists today, only dated operations. A photo control beside each plant's edit control opens a side view (double the width of existing side views) with paginated, lazy-loaded thumbnails; thumbnails expand to original size; upload and remove happen there. Applies to active and archived plants.

## Domain / Design Notes

```scala
opaque type PhotoId = UUID
final case class PlantPhoto(id: PhotoId, plantId: PlantId, capturedAt: Instant)

opaque type PhotoOffset = Int   // >= 0
opaque type PhotoPageSize = Int // 1..24
final case class PhotoWindow(offset: PhotoOffset, size: PhotoPageSize)
final case class PhotoPage(photos: Vector[PlantPhoto], hasNextPage: Boolean)

enum PhotoMediaType:
  case Jpeg, Png, Webp
final case class PhotoContent(bytes: ByteVector, mediaType: PhotoMediaType)

enum PhotoVariant:
  case Original, Thumbnail

trait PlantJournal:
  def addPhoto(plantId: PlantId, content: PhotoContent): AddPhotoResult
  def removePhoto(id: PhotoId): RemovePhotoResult
  def getPhotos(plantId: PlantId, window: PhotoWindow): GetPhotosResult

trait PlantJournalStore:
  def addPhoto(photo: PlantPhoto): AddPhotoResult
  def removePhoto(id: PhotoId): RemovePhotoResult
  def getPhotos(plantId: PlantId, window: PhotoWindow): GetPhotosResult

trait ThumbnailGenerator:
  def generate(content: PhotoContent): PhotoContent

trait PhotoContentStore:
  def put(id: PhotoId, variant: PhotoVariant, content: PhotoContent): PhotoWriteResult
  def get(id: PhotoId, variant: PhotoVariant): PhotoReadResult
  def delete(id: PhotoId): PhotoWriteResult
```

- `capturedAt` = upload time, server-assigned, not backdated.
- Order: capture time desc, then id desc. Same convention as operation history.
- `PlantJournalStore` persists `PlantPhoto` metadata in the relational journal; `PhotoContentStore` is a new, separate persistence port for binary content, keyed by `PhotoId` and `PhotoVariant`. Production backs it with files on the NAS array (not the cache disk); local development defaults to a local directory, same as the existing isolated local database default. Keeps the SQLite file and its backup path unaffected by photo volume.
- `addPhoto` derives a `Thumbnail` variant from the uploaded `Original` via `ThumbnailGenerator`, then writes both variants and the metadata row; a failure partway compensates whichever writes succeeded and reports both failures if compensation fails too — same pattern as existing repot compensation. `delete` removes both variants for that id in one call; a photo is never partially deleted.
- `ThumbnailGenerator` targets ~100KB per thumbnail — decent resolution for a grid, far below multi-MB originals.
- Photo content never changes after upload — same `PhotoId`/`PhotoVariant` always yields the same bytes — so responses are cacheable indefinitely; a removed photo simply stops being reachable from any listing, so a stale cached response is never re-requested.

## Invariants

- Local development runs fully isolated, without NAS connectivity, as it does today.

## Acceptance Criteria

- Photo control left of edit control, on active and archived plants; opens/closes the side view, focus managed on both.
- Grid of thumbnails, newest first, each labelled `dd.mm.yyyy hh:mm`; empty state when none; further pages load lazily on request; failed page fetch retries without losing loaded pages.
- The grid renders the `Thumbnail` variant; activating one opens the `Original` variant at full size above the side view; dismiss returns focus without closing the side view.
- A single add control (`+`) at the top of the grid opens the browser's native file picker; a chosen photo uploads and, on success, appears first in the grid. Rejected before storage if unsupported type or over the size bound, with error, no change to existing photos.
- Each thumbnail has its own remove control (`×`), gated by the existing irreversible-action confirmation (archive/delete-operation pattern); confirmed removal is permanent, no restore.
- Failed upload/removal leaves state unchanged with an accessible error.

## Doc Sync

- `specs/design.md` — Domain model: plant has unbounded, paginated dated photos, added/removed independently of operation history.
- `specs/contracts.md` — Contract inventory: add `PhotoContentStore` (storage) and `ThumbnailGenerator` (external capability) as new ports, alongside the existing store/capability entries. HTTP surface is unaffected — the new journal methods are covered by the existing generated-OpenAPI entry, no new row.
- `specs/testing.md` — Validation beyond isolated tests: lazy-loaded pagination and file upload depend on real browser interaction; validate in e2e, not isolated tests alone.
- `specs/operational.md` — Runtime dependencies: photo content directory as a new writable dependency (NAS array in production, a local directory in local development), alongside the existing SQLite path.

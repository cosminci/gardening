package gardening.adapters.file

import gardening.domain.plants.*
import gardening.domain.plants.PhotoMediaType.*
import munit.FunSuite
import scodec.bits.ByteVector

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.UUID

class FilePhotoContentStoreSeamIntegrationTest extends FunSuite:

  private val photoId          = PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000001"))
  private val jpegContent      = PhotoContent(ByteVector(Array[Byte](0xff.toByte, 0xd8.toByte, 0x01)), Jpeg)
  private val pngContent       = PhotoContent(ByteVector(Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47)), Png)
  private val thumbnailContent = PhotoContent(ByteVector(Array[Byte](0xff.toByte, 0xd8.toByte, 0x02)), Jpeg)

  test("should round-trip a jpeg original and its thumbnail"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      assertEquals(store.put(photoId, jpegContent, thumbnailContent), PhotoWriteResult.Written)
      assertEquals(store.get(photoId, PhotoVariant.Original), PhotoReadResult.Read(jpegContent))
      assertEquals(store.get(photoId, PhotoVariant.Thumbnail), PhotoReadResult.Read(thumbnailContent))

  test("should round-trip a png original alongside its (always-jpeg) thumbnail"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      assertEquals(store.put(photoId, pngContent, thumbnailContent), PhotoWriteResult.Written)
      assertEquals(store.get(photoId, PhotoVariant.Original), PhotoReadResult.Read(pngContent))
      assertEquals(store.get(photoId, PhotoVariant.Thumbnail), PhotoReadResult.Read(thumbnailContent))

  test("should return ContentMissing for an unknown photo id"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      assertEquals(store.get(photoId, PhotoVariant.Original), PhotoReadResult.ContentMissing)
      assertEquals(store.get(photoId, PhotoVariant.Thumbnail), PhotoReadResult.ContentMissing)

  test("should delete both variants of an existing photo without error"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      assertEquals(store.put(photoId, jpegContent, thumbnailContent), PhotoWriteResult.Written)
      assertEquals(store.delete(photoId), PhotoWriteResult.Written)
      assertEquals(store.get(photoId, PhotoVariant.Original), PhotoReadResult.ContentMissing)
      assertEquals(store.get(photoId, PhotoVariant.Thumbnail), PhotoReadResult.ContentMissing)

  test("should return Written when deleting a nonexistent photo"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      assertEquals(store.delete(photoId), PhotoWriteResult.Written)

  test("should create missing parent directories on put"):
    withTempDir: dir =>
      val nested = dir.resolve("a").resolve("b").resolve("c")
      val store  = FilePhotoContentStore.make(nested)
      assertEquals(store.put(photoId, jpegContent, thumbnailContent), PhotoWriteResult.Written)
      assertEquals(store.get(photoId, PhotoVariant.Original), PhotoReadResult.Read(jpegContent))

  test("should return WriteFailed when the directory path is a file"):
    withTempDir: dir =>
      val file  = dir.resolve("not-a-dir")
      val _     = Files.write(file, Array[Byte](1, 2, 3))
      val store = FilePhotoContentStore.make(file)
      store.put(photoId, jpegContent, thumbnailContent) match
        case PhotoWriteResult.WriteFailed(_: IOException) => ()
        case other                                        => fail(s"expected WriteFailed with IOException, got $other")

  test("should return ReadFailed when a found file cannot be read"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      // A directory at the expected path is unreadable as bytes regardless of file permissions or
      // user privilege (unlike a chmod-based fault, which root ignores), so this deterministically
      // exercises the IOException branch under any user, including CI's root.
      Files.createDirectory(dir.resolve(s"${photoId.value}-original.jpeg"))
      store.get(photoId, PhotoVariant.Original) match
        case PhotoReadResult.ReadFailed(_: IOException) => ()
        case other                                      => fail(s"expected ReadFailed, got $other")

  test("should return WriteFailed when an existing photo cannot be deleted"):
    withTempDir: dir =>
      val store    = FilePhotoContentStore.make(dir)
      val photoDir = dir.resolve(s"${photoId.value}-original.jpeg")
      // A non-empty directory at the expected path can never be deleted via deleteIfExists
      // (DirectoryNotEmptyException, an IOException), regardless of user privilege.
      Files.createDirectory(photoDir)
      val _ = Files.write(photoDir.resolve("child"), Array[Byte](1))
      store.delete(photoId) match
        case PhotoWriteResult.WriteFailed(_: IOException) => ()
        case other                                        => fail(s"expected WriteFailed, got $other")

  private def withTempDir(body: Path => Unit): Unit =
    val dir = Files.createTempDirectory("gardening-test-photos-")
    try body(dir)
    finally deleteRecursively(dir)

  private def deleteRecursively(path: Path): Unit =
    if Files.isDirectory(path) then
      Files.list(path).forEach(deleteRecursively)
    val _ = Files.deleteIfExists(path)

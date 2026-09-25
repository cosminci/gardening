package gardening.adapters.storage

import gardening.domain.journal.*
import gardening.domain.journal.PhotoMediaType.*
import munit.FunSuite
import scodec.bits.ByteVector

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.UUID

class FilePhotoContentStoreSeamIntegrationTest extends FunSuite:

  private val photoId     = PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000001"))
  private val jpegContent = PhotoContent(ByteVector(Array[Byte](0xff.toByte, 0xd8.toByte, 0x01)), Jpeg)
  private val pngContent  = PhotoContent(ByteVector(Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47)), Png)
  private val webpContent = PhotoContent(ByteVector(Array[Byte](0x52, 0x49, 0x46, 0x46)), Webp)

  test("should round-trip a jpeg photo"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      assertEquals(store.put(photoId, jpegContent), PhotoWriteResult.Written)
      assertEquals(store.get(photoId), PhotoReadResult.Read(jpegContent))

  test("should round-trip a png photo"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      assertEquals(store.put(photoId, pngContent), PhotoWriteResult.Written)
      assertEquals(store.get(photoId), PhotoReadResult.Read(pngContent))

  test("should round-trip a webp photo"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      assertEquals(store.put(photoId, webpContent), PhotoWriteResult.Written)
      assertEquals(store.get(photoId), PhotoReadResult.Read(webpContent))

  test("should return ContentMissing for an unknown photo id"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      assertEquals(store.get(photoId), PhotoReadResult.ContentMissing)

  test("should delete an existing photo without error"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      assertEquals(store.put(photoId, jpegContent), PhotoWriteResult.Written)
      assertEquals(store.delete(photoId), PhotoWriteResult.Written)
      assertEquals(store.get(photoId), PhotoReadResult.ContentMissing)

  test("should return Written when deleting a nonexistent photo"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      assertEquals(store.delete(photoId), PhotoWriteResult.Written)

  test("should create missing parent directories on put"):
    withTempDir: dir =>
      val nested = dir.resolve("a").resolve("b").resolve("c")
      val store  = FilePhotoContentStore.make(nested)
      assertEquals(store.put(photoId, jpegContent), PhotoWriteResult.Written)
      assertEquals(store.get(photoId), PhotoReadResult.Read(jpegContent))

  test("should return WriteFailed when the directory path is a file"):
    withTempDir: dir =>
      val file  = dir.resolve("not-a-dir")
      val _     = Files.write(file, Array[Byte](1, 2, 3))
      val store = FilePhotoContentStore.make(file)
      store.put(photoId, jpegContent) match
        case PhotoWriteResult.WriteFailed(_: IOException) => ()
        case other                                        => fail(s"expected WriteFailed with IOException, got $other")

  test("should return ReadFailed when a found file cannot be read"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      val _     = Files.write(dir.resolve(s"${photoId.value}.jpeg"), Array[Byte](1, 2, 3))
      dir.resolve(s"${photoId.value}.jpeg").toFile.setReadable(false)
      store.get(photoId) match
        case PhotoReadResult.ReadFailed(_: IOException) => ()
        case PhotoReadResult.Read(_)                    => () // pass if running as root or on permissive FS
        case other                                      => fail(s"expected ReadFailed or Read, got $other")

  test("should return WriteFailed when an existing photo cannot be deleted"):
    withTempDir: dir =>
      val store = FilePhotoContentStore.make(dir)
      assertEquals(store.put(photoId, jpegContent), PhotoWriteResult.Written)
      dir.toFile.setWritable(false)
      try
        store.delete(photoId) match
          case PhotoWriteResult.WriteFailed(_: IOException) => ()
          case PhotoWriteResult.Written                     => () // pass if running as root or on permissive FS
          case other                                        => fail(s"expected WriteFailed or Written, got $other")
      finally dir.toFile.setWritable(true): Unit

  private def withTempDir(body: Path => Unit): Unit =
    val dir = Files.createTempDirectory("gardening-test-photos-")
    try body(dir)
    finally deleteRecursively(dir)

  private def deleteRecursively(path: Path): Unit =
    if Files.isDirectory(path) then
      Files.list(path).forEach(deleteRecursively)
    val _ = Files.deleteIfExists(path)

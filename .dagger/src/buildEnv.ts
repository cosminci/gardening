/** Base images and shared constants for the pipeline. Centralised so an image bump is one edit. */
// Prebuilt, multi-arch JDK 25 + sbt image: no fragile network install, and it works under
// emulation (amd64 on an arm64 host) because it is pulled rather than assembled.
export const JDK_IMAGE = "sbtscala/scala-sbt:eclipse-temurin-25.0.4_7_1.13.0_3.8.4"; // builder only
export const NODE_IMAGE = "node:24-slim"; // builder only
/** Runtime base: distroless java-base carries the system libraries a JVM needs (glibc, zlib, …)
 * but no JRE, so the backend's bundled jlink runtime supplies Java. No shell, no package manager. */
export const RUNTIME_IMAGE = "gcr.io/distroless/java-base-debian12";

/** This app runs only on the NAS (x86_64), so images are always built for linux/amd64. */
export const TARGET_PLATFORM = "linux/amd64";

export const IMAGE_NAME = "plant-journal";
export const GHCR_REPOSITORY = "ghcr.io/cosminci/plant-journal";
export const GHCR_USER = "cosminci";
export const SOURCE_URL = "https://github.com/cosminci/gardening";
export const APP_PORT = 8080;

/** Heavy or generated trees the pipeline never needs; kept out of the uploaded build context.
 * `.git` is intentionally NOT ignored — version derivation and the release guard read it.
 */
export const WORKSPACE_IGNORE = [
  "**/node_modules",
  "**/target",
  "**/dist",
  "**/.bsp",
  "**/.scala-build",
  ".dagger/sdk",
];

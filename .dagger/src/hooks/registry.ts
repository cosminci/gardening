import { randomUUID } from "node:crypto";
import * as Dagger from "@dagger.io/dagger";
import * as BuildEnv from "../buildEnv";

const listTagsScript = `
import { listRegistryTags } from "/ci/registryClient.ts";
const image = process.env.GHCR_IMAGE;
const user = process.env.GHCR_USER;
const token = process.env.GHCR_TOKEN;
if (!image || !user || !token) throw new Error("GHCR credentials are not configured");
const tags = await listRegistryTags(fetch, image, user, token);
process.stdout.write(JSON.stringify(tags));
`;

const imageDigestScript = `
import { registryImageDigest } from "/ci/registryClient.ts";
const image = process.env.GHCR_IMAGE;
const user = process.env.GHCR_USER;
const token = process.env.GHCR_TOKEN;
const version = process.env.GHCR_VERSION;
if (!image || !user || !token || !version) throw new Error("GHCR image lookup is not configured");
process.stdout.write(await registryImageDigest(fetch, image, user, token, version));
`;

export async function publishedTags(
  source: Dagger.Directory,
  token: Dagger.Secret,
): Promise<string[]> {
  const output = await registryContainer(source, token)
    .withExec(["node", "--experimental-strip-types", "--input-type=module", "-e", listTagsScript])
    .stdout();
  const result: unknown = JSON.parse(output);
  if (!Array.isArray(result) || !result.every((tag) => typeof tag === "string")) {
    throw new Error("GHCR returned an invalid tag list");
  }
  return result;
}

export async function publishedImageDigest(
  source: Dagger.Directory,
  token: Dagger.Secret,
  version: string,
): Promise<string> {
  return registryContainer(source, token)
    .withEnvVariable("GHCR_VERSION", version)
    .withExec([
      "node",
      "--experimental-strip-types",
      "--input-type=module",
      "-e",
      imageDigestScript,
    ])
    .stdout();
}

function registryContainer(source: Dagger.Directory, token: Dagger.Secret) {
  return Dagger.dag
    .container()
    .from(BuildEnv.NODE_IMAGE)
    .withFile("/ci/registryClient.ts", source.file(".dagger/src/registryClient.ts"))
    .withFile("/ci/package.json", source.file(".dagger/package.json"))
    .withEnvVariable("GHCR_IMAGE", BuildEnv.GHCR_REPOSITORY.slice("ghcr.io/".length))
    .withEnvVariable("GHCR_USER", BuildEnv.GHCR_USER)
    .withSecretVariable("GHCR_TOKEN", token)
    .withEnvVariable("GHCR_REQUEST_ID", randomUUID());
}

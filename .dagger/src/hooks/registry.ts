import * as Dagger from "@dagger.io/dagger";
import * as BuildEnv from "../buildEnv";

const listTagsScript = `
const registry = "https://ghcr.io";
const image = process.env.GHCR_IMAGE;
const authorization = "Basic " + Buffer.from(process.env.GHCR_USER + ":" + process.env.GHCR_TOKEN).toString("base64");
const credential = await fetch(registry + "/token?scope=repository:" + image + ":pull&service=ghcr.io", {
  headers: { Authorization: authorization },
});
if (!credential.ok) throw new Error("GHCR authentication failed: HTTP " + credential.status);
const access = await credential.json();
if (typeof access.token !== "string") throw new Error("GHCR returned no registry token");

const tags = [];
let next = "/v2/" + image + "/tags/list";
while (next) {
  const url = new URL(next, registry);
  if (url.origin !== registry) throw new Error("GHCR returned an external pagination URL");
  const response = await fetch(url, { headers: { Authorization: "Bearer " + access.token } });
  if (response.status === 404 && tags.length === 0) break;
  if (!response.ok) throw new Error("GHCR tag listing failed: HTTP " + response.status);
  const page = await response.json();
  if (!Array.isArray(page.tags) || !page.tags.every((tag) => typeof tag === "string")) {
    throw new Error("GHCR returned an invalid tag list");
  }
  tags.push(...page.tags);
  const link = response.headers.get("link");
  const match = link && /<([^>]+)>;\\s*rel="next"/.exec(link);
  next = match ? match[1] : "";
}
process.stdout.write(JSON.stringify(tags));
`;

export async function publishedTags(token: Dagger.Secret): Promise<string[]> {
  const output = await Dagger.dag
    .container()
    .from(BuildEnv.NODE_IMAGE)
    .withEnvVariable("GHCR_IMAGE", BuildEnv.GHCR_REPOSITORY.slice("ghcr.io/".length))
    .withEnvVariable("GHCR_USER", BuildEnv.GHCR_USER)
    .withSecretVariable("GHCR_TOKEN", token)
    .withExec(["node", "--input-type=module", "-e", listTagsScript])
    .stdout();
  const result: unknown = JSON.parse(output);
  if (!Array.isArray(result) || !result.every((tag) => typeof tag === "string")) {
    throw new Error("GHCR returned an invalid tag list");
  }
  return result;
}

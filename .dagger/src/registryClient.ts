const REGISTRY = "https://ghcr.io";

export async function listRegistryTags(
  fetcher: typeof fetch,
  image: string,
  user: string,
  secret: string,
): Promise<string[]> {
  const token = await registryToken(fetcher, image, user, secret);
  const tags: string[] = [];
  let next: string | undefined = `/v2/${image}/tags/list`;
  let firstPage = true;
  while (next !== undefined) {
    const url = new URL(next, REGISTRY);
    if (url.origin !== REGISTRY) throw new Error("GHCR returned an external pagination URL");
    const response = await fetcher(url, { headers: { Authorization: `Bearer ${token}` } });
    if (response.status === 404 && firstPage) return [];
    if (!response.ok) throw new Error(`GHCR tag listing failed: HTTP ${String(response.status)}`);
    const page: unknown = await response.json();
    if (
      typeof page !== "object" ||
      page === null ||
      !("tags" in page) ||
      !isStringArray(page.tags)
    ) {
      throw new Error("GHCR returned an invalid tag list");
    }
    tags.push(...page.tags);
    const match = /<([^>]+)>;\s*rel="next"/.exec(response.headers.get("link") ?? "");
    next = match?.[1];
    firstPage = false;
  }
  return tags;
}

export async function registryImageDigest(
  fetcher: typeof fetch,
  image: string,
  user: string,
  secret: string,
  version: string,
): Promise<string> {
  const token = await registryToken(fetcher, image, user, secret);
  const response = await fetcher(
    new URL(`/v2/${image}/manifests/${encodeURIComponent(version)}`, REGISTRY),
    {
      method: "HEAD",
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: [
          "application/vnd.oci.image.index.v1+json",
          "application/vnd.oci.image.manifest.v1+json",
          "application/vnd.docker.distribution.manifest.list.v2+json",
          "application/vnd.docker.distribution.manifest.v2+json",
        ].join(", "),
      },
    },
  );
  if (!response.ok) throw new Error(`GHCR manifest lookup failed: HTTP ${String(response.status)}`);
  const digest = response.headers.get("docker-content-digest");
  if (!digest || !/^sha256:[a-f0-9]{64}$/.test(digest)) {
    throw new Error("GHCR returned no valid image digest");
  }
  return digest;
}

export function assertSameImage(versionedDigest: string, aliasDigest: string): void {
  if (versionedDigest !== aliasDigest) {
    throw new Error("GHCR latest points to a different image from the versioned release");
  }
}

async function registryToken(
  fetcher: typeof fetch,
  image: string,
  user: string,
  secret: string,
): Promise<string> {
  const authorization = `Basic ${Buffer.from(`${user}:${secret}`).toString("base64")}`;
  const credentialUrl = new URL("/token", REGISTRY);
  credentialUrl.searchParams.set("scope", `repository:${image}:pull`);
  credentialUrl.searchParams.set("service", "ghcr.io");
  const credential = await fetcher(credentialUrl, { headers: { Authorization: authorization } });
  if (!credential.ok)
    throw new Error(`GHCR authentication failed: HTTP ${String(credential.status)}`);
  const access: unknown = await credential.json();
  if (
    typeof access !== "object" ||
    access === null ||
    !("token" in access) ||
    typeof access.token !== "string"
  ) {
    throw new Error("GHCR returned no registry token");
  }
  return access.token;
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((entry: unknown) => typeof entry === "string");
}

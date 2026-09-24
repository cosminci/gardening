import { spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";

const nasScript = join(process.cwd(), "..", "scripts", "nas.sh");
const workspaces: string[] = [];

describe("NAS deployment script", () => {
  it("should deploy a healthy stable version with persistent data and WUD monitoring only", () => {
    const workspace = buildNasWorkspace();
    writeFileSync(join(workspace, "data", "gardening.db"), "journal");

    const result = runNas(workspace, ["deploy", "1.0.0"], { healthVersion: "1.0.0" });

    const commands = readCommands(workspace);
    expect(result.status).toBe(0);
    expect(commands).toContain("docker pull ghcr.io/cosminci/plant-journal:1.0.0");
    expect(commands.some((command) => command.includes("--label wud.watch=true"))).toBe(true);
    expect(
      commands.some((command) => command.includes("--label wud.trigger.exclude=docker.local")),
    ).toBe(true);
    expect(
      commands.some((command) => command.includes(`${join(workspace, "data")}:/data:rw`)),
    ).toBe(true);
    expect(readFileSync(join(workspace, "data", "gardening.db"), "utf8")).toBe("journal");
  });

  it("should keep a prerelease out of WUD and retain the old container until healthy", () => {
    const workspace = buildNasWorkspace();

    const result = runNas(workspace, ["deploy", "1.0.0-rc.1"], {
      existing: true,
      healthVersion: "1.0.0-rc.1",
    });

    const commands = readCommands(workspace);
    const stopIndex = commands.indexOf("docker stop plant-journal");
    const removeIndex = commands.indexOf("docker rm plant-journal-previous");
    expect(result.status).toBe(0);
    expect(commands.some((command) => command.includes("--label wud.watch=false"))).toBe(true);
    expect(stopIndex).toBeGreaterThan(
      commands.indexOf("docker pull ghcr.io/cosminci/plant-journal:1.0.0-rc.1"),
    );
    expect(removeIndex).toBeGreaterThan(stopIndex);
  });

  it("should preserve the existing container when a private pull fails", () => {
    const workspace = buildNasWorkspace();

    const result = runNas(workspace, ["deploy", "1.0.0"], { existing: true, failPull: true });

    const commands = readCommands(workspace);
    expect(result.status).not.toBe(0);
    expect(commands).not.toContain("docker stop plant-journal");
    expect(commands.some((command) => command.startsWith("docker run "))).toBe(false);
  });

  it("should report failed startup and retain the previous container for recovery", () => {
    const workspace = buildNasWorkspace();

    const result = runNas(workspace, ["deploy", "1.0.0"], {
      existing: true,
      healthVersion: "0.9.0",
    });

    const commands = readCommands(workspace);
    expect(result.status).not.toBe(0);
    expect(commands).toContain("docker rename plant-journal plant-journal-previous");
    expect(commands).not.toContain("docker rm plant-journal-previous");
  });

  it("should restore a selected periodic appdata backup before starting an older version", () => {
    const workspace = buildNasWorkspace();
    const archive = buildBackupArchive(workspace, "compatible-backup");
    writeFileSync(join(workspace, "data", "gardening.db"), "current-journal");

    const result = runNas(workspace, ["recover", "0.9.0", archive], {
      existing: true,
      healthVersion: "0.9.0",
    });

    const preservedDir = readdirSync(workspace).find((name) =>
      name.startsWith("data.before-restore."),
    );
    expect(result.status).toBe(0);
    expect(readFileSync(join(workspace, "data", "gardening.db"), "utf8")).toBe("compatible-backup");
    expect(preservedDir).toBeDefined();
    expect(readFileSync(join(workspace, preservedDir ?? "", "gardening.db"), "utf8")).toBe(
      "current-journal",
    );
  });

  it("should restore the running container and journal when recovered health fails", () => {
    const workspace = buildNasWorkspace();
    const archive = buildBackupArchive(workspace, "compatible-backup");
    writeFileSync(join(workspace, "data", "gardening.db"), "current-journal");

    const result = runNas(workspace, ["recover", "0.9.0", archive], {
      existing: true,
      healthVersion: "incorrect-version",
    });

    const commands = readCommands(workspace);
    expect(result.status).not.toBe(0);
    expect(commands).toContain("docker rename plant-journal-before-recovery plant-journal");
    expect(commands).toContain("docker start plant-journal");
    expect(readFileSync(join(workspace, "data", "gardening.db"), "utf8")).toBe("current-journal");
  });

  it("should refuse invalid versions and missing recovery backups before replacing anything", () => {
    const invalidWorkspace = buildNasWorkspace();
    const missingBackupWorkspace = buildNasWorkspace();

    const invalid = runNas(invalidWorkspace, ["deploy", "latest"]);
    const missingBackup = runNas(missingBackupWorkspace, [
      "recover",
      "1.0.0",
      join(missingBackupWorkspace, "missing.tar.gz"),
    ]);

    expect(invalid.status).not.toBe(0);
    expect(missingBackup.status).not.toBe(0);
    expect(existsSync(join(invalidWorkspace, "steps"))).toBe(false);
    expect(existsSync(join(missingBackupWorkspace, "steps"))).toBe(false);
  });

  it("should reject a backup belonging to another app before stopping the journal", () => {
    const workspace = buildNasWorkspace();
    const otherAppDir = join(workspace, "backup", "mnt", "user", "appdata", "another-app");
    const archive = join(workspace, "another-app.tar.gz");
    mkdirSync(otherAppDir, { recursive: true });
    writeFileSync(join(otherAppDir, "gardening.db"), "wrong-app");
    const tar = spawnSync(
      "tar",
      ["-czf", archive, "-C", join(workspace, "backup"), "mnt/user/appdata/another-app"],
      { encoding: "utf8" },
    );

    const result = runNas(workspace, ["recover", "0.9.0", archive], { existing: true });

    expect(tar.status).toBe(0);
    expect(result.status).not.toBe(0);
    expect(existsSync(join(workspace, "steps"))).toBe(false);
  });
});

afterEach(() => {
  for (const workspace of workspaces) rmSync(workspace, { recursive: true, force: true });
  workspaces.length = 0;
});

function buildBackupArchive(workspace: string, journal: string): string {
  const relativeDataDir = join(workspace, "data").replace(/^\//, "");
  const backupDir = join(workspace, "backup", relativeDataDir);
  const archive = join(workspace, "plant-journal.tar.gz");
  mkdirSync(backupDir, { recursive: true });
  writeFileSync(join(backupDir, "gardening.db"), journal);
  const result = spawnSync(
    "tar",
    ["-czf", archive, "-C", join(workspace, "backup"), relativeDataDir],
    { encoding: "utf8" },
  );
  if (result.status !== 0) throw new Error(`Cannot make test backup: ${result.stderr}`);
  return archive;
}

function buildNasWorkspace(): string {
  const workspace = mkdtempSync(join(tmpdir(), "plant-journal-nas-"));
  workspaces.push(workspace);
  mkdirSync(join(workspace, "bin"));
  mkdirSync(join(workspace, "data"));
  writeFileSync(
    join(workspace, "bin", "docker"),
    `#!/bin/sh
printf "docker %s\\n" "$*" >> "$NAS_LOG"
if [ "$1" = "container" ] && [ "$2" = "inspect" ]; then
  case "$3" in
    plant-journal-previous) [ "$NAS_PREVIOUS" = "true" ] ;;
    plant-journal-before-recovery) [ "$NAS_BEFORE_RECOVERY" = "true" ] ;;
    plant-journal) [ "$NAS_EXISTING" = "true" ] ;;
    *) exit 1 ;;
  esac
elif [ "$1" = "inspect" ]; then
  case "$3" in
    *Mounts*) printf "%s\\n" "$NAS_DATA_DIR" ;;
    *PortBindings*) printf "8080\\n" ;;
    *NetworkMode*) printf "bridge\\n" ;;
    *Config.Image*) printf "ghcr.io/cosminci/plant-journal:0.9.0\\n" ;;
  esac
elif [ "$1" = "pull" ]; then
  [ "$NAS_FAIL_PULL" != "true" ]
fi
`,
    { mode: 0o755 },
  );
  writeFileSync(
    join(workspace, "bin", "curl"),
    '#!/bin/sh\nprintf \'{"status":"ok","version":"%s"}\\n\' "$NAS_HEALTH_VERSION"\n',
    { mode: 0o755 },
  );
  writeFileSync(
    join(workspace, "bin", "jq"),
    '#!/bin/sh\nIFS= read -r body\n[ "$body" = "{\\"status\\":\\"ok\\",\\"version\\":\\"$4\\"}" ]\n',
    { mode: 0o755 },
  );
  writeFileSync(join(workspace, "bin", "chown"), "#!/bin/sh\nexit 0\n", { mode: 0o755 });
  return workspace;
}

function runNas(
  workspace: string,
  args: string[],
  options: {
    existing?: boolean;
    previous?: boolean;
    failPull?: boolean;
    healthVersion?: string;
  } = {},
) {
  const inheritedPath = process.env.PATH;
  if (!inheritedPath) throw new Error("PATH must be set to run NAS use cases");
  return spawnSync("bash", [nasScript, ...args], {
    cwd: workspace,
    encoding: "utf8",
    env: {
      ...process.env,
      PATH: `${join(workspace, "bin")}${delimiter}${inheritedPath}`,
      NAS_LOG: join(workspace, "steps"),
      NAS_DATA_DIR: join(workspace, "data"),
      NAS_EXISTING: String(options.existing ?? false),
      NAS_PREVIOUS: String(options.previous ?? false),
      NAS_BEFORE_RECOVERY: "false",
      NAS_FAIL_PULL: String(options.failPull ?? false),
      NAS_HEALTH_VERSION: options.healthVersion ?? "",
      PLANT_JOURNAL_DATA_DIR: join(workspace, "data"),
      PLANT_JOURNAL_HEALTH_ATTEMPTS: "1",
    },
  });
}

function readCommands(workspace: string): string[] {
  return readFileSync(join(workspace, "steps"), "utf8").trim().split("\n");
}

import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import { constants } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = join(import.meta.dirname, "..");

async function assertMissing(relativePath) {
  await assert.rejects(
    () => access(join(root, relativePath), constants.F_OK),
    { code: "ENOENT" },
    `${relativePath} must be removed`,
  );
}

test("removed Maven distribution is absent", async () => {
  for (const path of [
    "src/main/dist/run.cmd",
    "src/main/dist/VERSION.txt",
    "src/main/assembly/dist.xml",
  ]) {
    await assertMissing(path);
  }
  const pom = await readFile(join(root, "pom.xml"), "utf8");
  assert.doesNotMatch(pom, /maven-assembly-plugin/);
  assert.match(pom, /spring-boot-maven-plugin/);
});

test("Windows executable branding has one canonical icon and build-time resource hooks", async () => {
  const icon = await readFile(join(root, "assets/branding/harmonia-suite.ico"));
  assert.ok(icon.length > 0);

  for (const path of [
    "apps/desktop/icon.ico",
    "apps/installer/icon.ico",
    "src/main/dist/yuki-icon.ico",
  ]) {
    await assertMissing(path);
  }

  const desktopPackaging = await readFile(
    join(root, "apps/desktop/scripts/package-payload.mjs"),
    "utf8",
  );
  assert.match(desktopPackaging, /assets.*branding.*harmonia-suite\.ico/);
  assert.match(desktopPackaging, /applyWindowsIcon/);

  const installerBuild = await readFile(join(root, "apps/installer/build.rs"), "utf8");
  assert.match(installerBuild, /CARGO_CFG_TARGET_OS/);
  assert.match(installerBuild, /winresource::WindowsResource/);
  assert.match(installerBuild, /harmonia-suite\.ico/);
});

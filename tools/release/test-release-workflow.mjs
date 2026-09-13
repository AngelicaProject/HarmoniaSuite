import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";

const workflowPath = join(
  import.meta.dirname,
  "..",
  "..",
  ".github",
  "workflows",
  "release.yml",
);
const workflow = await readFile(workflowPath, "utf8");

test("production release supports immutable tag recovery through workflow dispatch", () => {
  assert.match(workflow, /workflow_dispatch:\s*\n\s+inputs:\s*\n\s+tag:/);
  assert.match(
    workflow,
    /RELEASE_TAG_INPUT: \$\{\{ github\.event_name == 'workflow_dispatch' && inputs\.tag \|\| github\.ref_name \}\}/,
  );
  assert.match(workflow, /release tag must be vX\.Y\.Z/);
  assert.match(workflow, /release_tag=\$tag/);
});

test("build and publish jobs check out and verify the resolved exact tag", () => {
  assert.equal(
    workflow.match(
      /ref: \$\{\{ needs\.resolve-release\.outputs\.release_tag \}\}/g,
    )?.length,
    2,
  );
  assert.equal(workflow.match(/git rev-list -n 1 "\$RELEASE_TAG"/g)?.length, 3);
  assert.equal(
    workflow.match(/test "\$release_commit" = "\$tag_commit"/g)?.length,
    3,
  );
  assert.doesNotMatch(workflow, /GITHUB_SHA/);
  assert.doesNotMatch(workflow, /GITHUB_REF_NAME/);
});

test("matrix build configures LF checkouts before checking out the release tag", () => {
  const buildJob = workflow.match(/  verify-and-build:[\s\S]*?(?=\n  publish:)/)?.[0];
  assert.ok(buildJob);
  const lineEndingStep = buildJob.indexOf("- name: Configure deterministic Git line endings");
  const checkoutStep = buildJob.indexOf("- uses: actions/checkout@v4");
  assert.ok(lineEndingStep >= 0);
  assert.ok(checkoutStep > lineEndingStep);
  assert.match(
    buildJob,
    /git config --global core\.autocrlf false[\s\S]*git config --global core\.eol lf/,
  );
  assert.match(
    buildJob,
    /ref: \$\{\{ needs\.resolve-release\.outputs\.release_tag \}\}/,
  );
});

test("release identity is derived from the checked-out tag commit", () => {
  assert.match(workflow, /HARMONIA_RELEASE_SEED_COMMIT=\$release_commit/);
  assert.match(
    workflow,
    /HARMONIA_RELEASE_PRODUCT_VERSION=\$\{RELEASE_TAG#v\}/,
  );
  assert.match(
    workflow,
    /create-release-metadata\.mjs --commit "\$release_commit"/,
  );
});

test("release workflow installs the Rust components used by its checks", () => {
  assert.match(
    workflow,
    /dtolnay\/rust-toolchain@1\.89\.0\s*\n\s+with:\s*\n\s+components: rustfmt, clippy/,
  );
});

test("release staging and optional Windows signing use the manifest target directory", () => {
  assert.match(
    workflow,
    /cargo build --manifest-path apps\/installer\/Cargo\.toml --release --bins/,
  );
  assert.doesNotMatch(workflow, /(?<!apps\/installer\/)target\/release\//);
  for (const binary of [
    "HarmoniaSetup.exe",
    "HarmoniaSuite.exe",
    "harmonia-setup",
    "harmonia-suite",
  ]) {
    assert.match(
      workflow,
      new RegExp(`apps/installer/target/release/${binary}`),
    );
  }
  const signingStep = workflow.match(
    /- name: Sign Windows binaries[\s\S]*?(?=\n\s+- name: Stage release files \(Unix\))/,
  )?.[0];
  assert.ok(signingStep);
  assert.match(
    signingStep,
    /signtool sign[\s\S]*apps\/installer\/target\/release\/HarmoniaSetup\.exe/,
  );
  assert.match(
    signingStep,
    /signtool sign[\s\S]*apps\/installer\/target\/release\/HarmoniaSuite\.exe/,
  );
  assert.match(
    signingStep,
    /signtool verify[\s\S]*apps\/installer\/target\/release\/HarmoniaSetup\.exe/,
  );
  assert.match(
    signingStep,
    /signtool verify[\s\S]*apps\/installer\/target\/release\/HarmoniaSuite\.exe/,
  );
  assert.match(signingStep, /if: runner\.os == 'Windows'/);
  assert.match(
    signingStep,
    /Authenticode credentials are absent; release binaries remain unsigned/,
  );
});

test("Linux checksum entries are basename-only for downloaded artifacts", () => {
  const stagingStep = workflow.match(
    /- name: Stage release files \(Unix\)[\s\S]*?(?=\n\s+- name: Stage release files \(Windows\))/,
  )?.[0];
  assert.ok(stagingStep);
  assert.match(
    stagingStep,
    /cd release[\s\S]*sha256sum harmonia-setup harmonia-suite > sha256sums-linux\.txt/,
  );
  assert.doesNotMatch(
    stagingStep,
    /sha256sum release\/harmonia-setup release\/harmonia-suite/,
  );
});

test("Windows release contract runs in an explicit bash shell", () => {
  const contractStep = workflow.match(
    /- name: Check product version contract and release tag[\s\S]*?(?=\n\s+- name:|\n\s+- uses:)/,
  )?.[0];
  assert.ok(contractStep);
  assert.match(contractStep, /shell: bash/);
  assert.match(contractStep, /check-release\.mjs "\$RELEASE_TAG"/);
});

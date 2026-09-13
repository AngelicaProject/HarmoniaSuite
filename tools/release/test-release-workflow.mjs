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

test("Windows release contract runs in an explicit bash shell", () => {
  const contractStep = workflow.match(
    /- name: Check product version contract and release tag[\s\S]*?(?=\n\s+- name:|\n\s+- uses:)/,
  )?.[0];
  assert.ok(contractStep);
  assert.match(contractStep, /shell: bash/);
  assert.match(contractStep, /check-release\.mjs "\$RELEASE_TAG"/);
});

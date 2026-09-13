import assert from "node:assert/strict";
import test from "node:test";

import { decideRollingPublication } from "./rolling-policy.mjs";

const A = "a".repeat(40);
const B = "b".repeat(40);
const C = "c".repeat(40);

test("first and descendant green commits publish", () => {
  const ancestor = (left, right) => left === A && right === B;
  assert.equal(decideRollingPublication(A, "", ancestor), "publish");
  assert.equal(decideRollingPublication(B, A, ancestor), "publish");
});

test("late ancestor completion is a no-op", () => {
  assert.equal(decideRollingPublication(A, B, (left, right) => left === A && right === B), "noop");
});

test("same SHA is rerun-safe", () => {
  assert.equal(decideRollingPublication(B, B, () => { throw new Error("must not inspect ancestry"); }), "publish");
});

test("unrelated history fails closed", () => {
  assert.throws(
    () => decideRollingPublication(C, B, () => false),
    /unrelated rolling history/,
  );
});

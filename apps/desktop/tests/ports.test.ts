import { describe, expect, it } from "vitest";

import { findFreeLoopbackPort } from "../src/ports.js";

describe("dynamic local gateway port", () => {
  it("reserves a non-zero loopback TCP port", async () => {
    const port = await findFreeLoopbackPort();
    expect(port).toBeGreaterThan(0);
    expect(port).toBeLessThanOrEqual(65535);
  });
});

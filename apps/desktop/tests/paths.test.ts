import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

import { userDataRoot } from "../src/paths.js";

describe("userDataRoot", () => {
  it("uses the installer-provided root exactly in installed mode", () => {
    const explicit = resolve("phase6", "user data with spaces");
    expect(
      userDataRoot({
        HARMONIA_RUNTIME_MODE: "installed",
        HARMONIA_USER_DATA_ROOT: explicit,
        XDG_DATA_HOME: "/ignored",
        APPDATA: "C:\\ignored",
      }),
    ).toBe(explicit);
  });

  it("does not use launcher mode to change the installed user-data contract", () => {
    expect(userDataRoot({ HARMONIA_RUNTIME_MODE: "installed", APPDATA: "C:\\Users\\test\\AppData\\Roaming" })).toBe(
      process.platform === "win32"
        ? "C:\\Users\\test\\AppData\\Roaming\\HarmoniaSuite"
        : userDataRoot({ APPDATA: "C:\\Users\\test\\AppData\\Roaming" }),
    );
    expect(() =>
      userDataRoot({
        HARMONIA_USER_DATA_ROOT: "relative/user-data",
      }),
    ).toThrow("absolute path");
  });

  it("keeps the platform development fallback separate", () => {
    if (process.platform === "win32") {
      expect(userDataRoot({ APPDATA: "C:\\Users\\test\\AppData\\Roaming" })).toBe(
        "C:\\Users\\test\\AppData\\Roaming\\HarmoniaSuite",
      );
    } else {
      expect(userDataRoot({ XDG_DATA_HOME: "/tmp/test-xdg" })).toBe(
        "/tmp/test-xdg/harmonia-suite-data",
      );
    }
  });
});

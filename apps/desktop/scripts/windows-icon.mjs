import { stat } from "node:fs/promises";

import rcedit from "rcedit";

export async function applyWindowsIcon(executable, icon) {
  if (process.platform !== "win32") {
    throw new Error("Windows executable branding can only run on Windows");
  }
  await assertFile(executable, "Windows executable to brand");
  await assertFile(icon, "canonical Windows application icon");
  await rcedit(executable, { icon });
}

async function assertFile(path, label) {
  try {
    const info = await stat(path);
    if (!info.isFile()) throw new Error(`${label} is not a file: ${path}`);
  } catch (error) {
    throw new Error(`${label} is missing: ${path}`, { cause: error });
  }
}

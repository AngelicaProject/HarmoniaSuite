import { isAbsolute, join } from "node:path";

import { installedUserDataRoot } from "./runtime-context.js";

export function userDataRoot(env: NodeJS.ProcessEnv = process.env): string {
  // This helper remains as a small development/test override. Installed production startup
  // obtains userDataRoot from DesktopRuntimeContext and never needs launcher-provided values.
  if (env.HARMONIA_USER_DATA_ROOT) {
    if (!isAbsolute(env.HARMONIA_USER_DATA_ROOT)) {
      throw new Error("HARMONIA_USER_DATA_ROOT must be an absolute path");
    }
    return env.HARMONIA_USER_DATA_ROOT;
  }
  return installedUserDataRoot(env);
}

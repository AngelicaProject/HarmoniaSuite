import { homedir } from "node:os";
import { join } from "node:path";

export function userDataRoot(env: NodeJS.ProcessEnv = process.env): string {
  if (process.platform === "win32") {
    return join(env.APPDATA || join(homedir(), "AppData", "Roaming"), "HarmoniaSuite");
  }
  return join(env.XDG_DATA_HOME || join(homedir(), ".local", "share"), "harmonia-suite-data");
}

import { deltaApi } from "./delta";
import { exportsApi } from "./exports";
import { jobsApi } from "./jobs";
import { packApi } from "./pack";
import { projectsApi } from "./projects";
import { settingsApi } from "./settings";
import { sourceApi } from "./source";
import { systemApi } from "./system";

export * from "./requestTypes";
export { esc, query } from "./transport";

/** Stable application facade assembled from backend domain clients. */
export const api = {
  ...projectsApi,
  ...jobsApi,
  ...systemApi,
  ...settingsApi,
  ...sourceApi,
  ...packApi,
  ...exportsApi,
  ...deltaApi,
};

import type { Job } from "./types";
import type { JobRequest } from "./requestTypes";
import { mapJob } from "./mappers";
import { encode, request } from "./transport";

export const jobsApi = {
  startJob: (body: JobRequest): Promise<Job> =>
    request<unknown>("/api/jobs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        action: body.action,
        project_id: body.projectId,
        root: body.root,
        output: body.output,
        files: body.files,
        force: body.force,
        model: body.model,
        reasoning: body.reasoning,
      }),
    }).then(mapJob),
  jobGet: (jobId: string): Promise<Job> =>
    request<unknown>(`/api/jobs/${encode(jobId)}`).then(mapJob),
  jobCancel: (jobId: string): Promise<Job> =>
    request<unknown>(`/api/jobs/${encode(jobId)}`, { method: "DELETE" }).then(
      mapJob,
    ),
};

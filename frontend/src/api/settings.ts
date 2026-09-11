import type {
  AiModel,
  AiStatus,
  Backup,
  BackupList,
  SourceSettings,
} from "./types";
import { mapBackupList } from "./mappers";
import { encode, request } from "./transport";

export const settingsApi = {
  aiSettings: (): Promise<AiStatus> => request<AiStatus>("/api/settings/ai"),
  saveAiSettings: (body: {
    provider?: string;
    geminiKey?: string;
    openrouterKey?: string;
    openrouterModel?: string;
    openrouterReasoning?: string;
  }): Promise<AiStatus> =>
    request<AiStatus>("/api/settings/ai", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        provider: body.provider,
        gemini_key: body.geminiKey,
        openrouter_key: body.openrouterKey,
        openrouter_model: body.openrouterModel,
        openrouter_reasoning: body.openrouterReasoning,
      }),
    }),
  settings: (): Promise<SourceSettings> =>
    request<SourceSettings>("/api/settings"),
  saveSettings: (body: { gamePath?: string }): Promise<SourceSettings> =>
    request<SourceSettings>("/api/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ game_path: body.gamePath }),
    }),
  checkAiKey: (body: {
    provider?: string;
    key?: string;
  }): Promise<Record<string, unknown>> =>
    request<Record<string, unknown>>("/api/settings/ai/check", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ provider: body.provider, key: body.key }),
    }),
  aiModels: (): Promise<AiModel[]> =>
    request<AiModel[]>("/api/settings/ai/models"),
  detectSettings: (): Promise<{ gamePath?: string }> =>
    request<{ gamePath?: string }>("/api/settings/detect"),
  backups: (): Promise<BackupList> =>
    request<Record<string, unknown>>("/api/backup").then(mapBackupList),
  createBackup: (): Promise<Backup> =>
    request<Backup>("/api/backup", { method: "POST" }),
  saveBackupSettings: (
    retention?: number,
    autoIntervalMinutes?: number,
  ): Promise<BackupList> =>
    request<Record<string, unknown>>("/api/backup/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        retention,
        auto_interval_minutes: autoIntervalMinutes,
      }),
    }).then(mapBackupList),
  deleteBackup: (name: string): Promise<void> =>
    request<void>(`/api/backup/${encode(name)}`, { method: "DELETE" }),
  openBackupFolder: (): Promise<void> =>
    request<void>("/api/backup/open-folder", { method: "POST" }),
  backupDownloadUrl: (name: string): string => `/api/backup/${encode(name)}`,
};

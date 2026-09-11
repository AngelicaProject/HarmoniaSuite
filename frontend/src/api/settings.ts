import type {
  AiModel,
  AiStatus,
  Backup,
  BackupList,
  SourceSettings,
} from "./types";
import {
  mapAiModels,
  mapAiStatus,
  mapBackup,
  mapBackupList,
  mapDetectedSettings,
  mapSourceSettings,
} from "./mappers";
import { encode, record, request } from "./transport";

export const settingsApi = {
  aiSettings: (): Promise<AiStatus> =>
    request("/api/settings/ai").then(mapAiStatus),
  saveAiSettings: (body: {
    provider?: string;
    geminiKey?: string;
    openrouterKey?: string;
    openrouterModel?: string;
    openrouterReasoning?: string;
  }): Promise<AiStatus> =>
    request("/api/settings/ai", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        provider: body.provider,
        gemini_key: body.geminiKey,
        openrouter_key: body.openrouterKey,
        openrouter_model: body.openrouterModel,
        openrouter_reasoning: body.openrouterReasoning,
      }),
    }).then(mapAiStatus),
  settings: (): Promise<SourceSettings> =>
    request("/api/settings").then(mapSourceSettings),
  saveSettings: (body: { gamePath?: string }): Promise<SourceSettings> =>
    request("/api/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ game_path: body.gamePath }),
    }).then(mapSourceSettings),
  checkAiKey: (body: {
    provider?: string;
    key?: string;
  }): Promise<Record<string, unknown>> =>
    request("/api/settings/ai/check", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ provider: body.provider, key: body.key }),
    }).then(record),
  aiModels: (): Promise<AiModel[]> =>
    request("/api/settings/ai/models").then(mapAiModels),
  detectSettings: (): Promise<{ gamePath?: string }> =>
    request("/api/settings/detect").then(mapDetectedSettings),
  backups: (): Promise<BackupList> =>
    request("/api/backup").then(mapBackupList),
  createBackup: (): Promise<Backup> =>
    request("/api/backup", { method: "POST" }).then(mapBackup),
  saveBackupSettings: (
    retention?: number,
    autoIntervalMinutes?: number,
  ): Promise<BackupList> =>
    request("/api/backup/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        retention,
        auto_interval_minutes: autoIntervalMinutes,
      }),
    }).then(mapBackupList),
  deleteBackup: (name: string): Promise<void> =>
    request(`/api/backup/${encode(name)}`, { method: "DELETE" }).then(
      () => undefined,
    ),
  openBackupFolder: (): Promise<void> =>
    request("/api/backup/open-folder", { method: "POST" }).then(
      () => undefined,
    ),
  backupDownloadUrl: (name: string): string => `/api/backup/${encode(name)}`,
};

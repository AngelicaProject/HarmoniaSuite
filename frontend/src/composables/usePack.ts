import { ref, type Ref } from "vue";
import { api } from "../api/client";
import type { PackMeta, PackResponse, SourceSettings } from "../api/types";

export interface PackController {
  pack: Ref<PackMeta | null>;
  packErrors: Ref<string[]>;
  packManifest: Ref<Record<string, unknown> | null>;
  packMsg: Ref<string>;
  packCompat: Ref<string>;
  packLangs: Ref<string>;
  loadPack: () => Promise<void>;
  savePack: () => Promise<void>;
  addAuthor: () => void;
  deleteAuthor: (index: number) => void;
  reset: () => void;
}

export function usePack(
  projectId: Ref<string>,
  sourceStatus: Ref<SourceSettings>,
  log: (message: string) => void,
): PackController {
  const pack = ref<PackMeta | null>(null);
  const packErrors = ref<string[]>([]);
  const packManifest = ref<Record<string, unknown> | null>(null);
  const packMsg = ref("");
  const packCompat = ref("");
  const packLangs = ref("");

  function blankPack(): PackMeta {
    return {
      packId: "",
      translationVersion: "",
      gameVersion: "",
      compatibleGameVersions: [],
      vendorId: "",
      vendorName: "",
      vendorUrl: "",
      vendorContact: "",
      authors: [],
      languages: ["ru"],
      title: "",
      description: "",
      changelog: "",
      homepage: "",
      license: "",
      minPluginVersion: "",
    };
  }

  function applyPack(response: PackResponse): void {
    pack.value = Object.assign(blankPack(), response.pack || {});
    const current = pack.value;
    if (!current) return;
    if (!current.gameVersion && sourceStatus.value.gameVersion)
      current.gameVersion = sourceStatus.value.gameVersion;
    if (!Array.isArray(current.authors)) current.authors = [];
    packCompat.value = (current.compatibleGameVersions || []).join(", ");
    packLangs.value = (current.languages || []).join(", ");
    packErrors.value = response.errors || [];
    packManifest.value = response.manifest || null;
  }

  async function loadPack(): Promise<void> {
    if (!projectId.value || pack.value) return;
    try {
      applyPack(await api.getPack(projectId.value));
      packMsg.value = "";
    } catch (error) {
      packMsg.value = errorMessage(error);
    }
  }

  function addAuthor(): void {
    if (!pack.value) return;
    const authors = pack.value.authors || (pack.value.authors = []);
    authors.push({ name: "", role: "" });
  }

  function deleteAuthor(index: number): void {
    pack.value?.authors?.splice(index, 1);
  }

  async function savePack(): Promise<void> {
    if (!pack.value) return;
    packMsg.value = "";
    const value = Object.assign({}, pack.value, {
      compatibleGameVersions: packCompat.value
        .split(",")
        .map((item) => item.trim())
        .filter(Boolean),
      languages: packLangs.value
        .split(",")
        .map((item) => item.trim())
        .filter(Boolean),
      authors: (pack.value.authors || [])
        .filter((author) => author && (author.name || "").trim())
        .map((author) => ({
          name: (author.name || "").trim(),
          role: (author.role || "").trim(),
        })),
    });
    try {
      const response = await api.savePack(projectId.value, value);
      applyPack(response);
      packMsg.value =
        response.errors && response.errors.length
          ? "Сохранено, но манифест невалиден"
          : "Сохранено";
      log("\nНастройки пака сохранены");
    } catch (error) {
      packMsg.value = errorMessage(error);
    }
  }

  function reset(): void {
    pack.value = null;
    packErrors.value = [];
    packManifest.value = null;
    packMsg.value = "";
    packCompat.value = "";
    packLangs.value = "";
  }

  return {
    pack,
    packErrors,
    packManifest,
    packMsg,
    packCompat,
    packLangs,
    loadPack,
    savePack,
    addAuthor,
    deleteAuthor,
    reset,
  };
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

import { shallowRef, type Ref } from "vue";
import type { Entry, FileStats } from "../api/types";
import {
  replaceEntryInGroups,
  replaceEntryInRows,
  updateFileStats,
} from "../domain/entryReconciliation";
import { mergeEntries as mergeDocumentEntries } from "../domain/fileRows";
import type {
  FileRowState,
  ProjectDocument,
  SavedEntry,
} from "../domain/workspace";

export interface EntryMutations {
  savedEntry: Ref<SavedEntry | null>;
  localEntry: (id: string) => Entry | null;
  mergeEntries: (entries: Entry[]) => void;
  applySavedEntry: (previous: Entry | null, entry: Entry) => SavedEntry | null;
}

export interface EntryMutationOptions {
  document: Ref<ProjectDocument | null>;
  fileTree: Ref<FileStats[]>;
  fileRows: Ref<FileRowState>;
  findCachedEntry: (id: string) => Entry | null;
  replaceEntryInPreview: (entry: Entry) => void;
  reconcilePending: (previous: Entry | null, next: Entry) => void;
  onCacheChanged?: () => void;
}

export function useEntryMutations(
  options: EntryMutationOptions,
): EntryMutations {
  const savedEntry = shallowRef<SavedEntry | null>(null);

  function localEntry(id: string): Entry | null {
    return options.findCachedEntry(id);
  }

  function mergeEntries(entries: Entry[]): void {
    if (!entries.length) return;
    options.document.value = mergeDocumentEntries(
      options.document.value,
      entries,
    );
    options.onCacheChanged?.();
  }

  function applySavedEntry(
    previous: Entry | null,
    entry: Entry,
  ): SavedEntry | null {
    if (!entry?.id) return null;
    options.fileTree.value = updateFileStats(
      options.fileTree.value,
      previous,
      entry,
    );
    options.reconcilePending(previous, entry);
    mergeEntries([entry]);

    const rows = options.fileRows.value;
    if (rows.file === entry.file) {
      const result = replaceEntryInGroups(rows.groups, entry);
      if (result.changed) {
        options.fileRows.value = { ...rows, groups: result.groups };
      }
    }
    options.replaceEntryInPreview(entry);
    const fileStats = options.fileTree.value.find(
      (file) => file.path === entry.file,
    );
    const result: SavedEntry = {
      previous,
      entry,
      fileStats: fileStats ? { ...fileStats } : null,
    };
    savedEntry.value = result;
    return result;
  }

  return { savedEntry, localEntry, mergeEntries, applySavedEntry };
}

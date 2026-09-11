import type { FileStats } from "../api/types";

export interface FileTreeDirectory {
  name: string;
  path: string;
  dirs: Map<string, FileTreeDirectory>;
  files: FileStats[];
  total?: number;
  done?: number;
}

export type FileTreeRow =
  | {
      type: "dir";
      depth: number;
      key: string;
      dir: FileTreeDirectory;
      open: boolean;
      need: number;
    }
  | {
      type: "file";
      depth: number;
      key: string;
      file: FileStats;
      need: number;
    };

export type FileTreeSort = "name" | "progress" | "need";

interface TreeNode {
  dirs: Map<string, FileTreeDirectory>;
  files: FileStats[];
}

export function buildFileTree(
  files: FileStats[],
  options: {
    query?: string;
    hideEmpty?: boolean;
    hideReady?: boolean;
    sort?: FileTreeSort;
    expanded?: Record<string, boolean>;
  } = {},
): FileTreeRow[] {
  const query = (options.query || "").trim().toLowerCase();
  const sort = options.sort || "name";
  const expanded = options.expanded || {};
  const root: TreeNode = { dirs: new Map(), files: [] };

  const visible = (file: FileStats): boolean => {
    if (query && !file.path.toLowerCase().includes(query)) return false;
    if (options.hideEmpty && file.total === 0) return false;
    if (options.hideReady && file.total <= file.translated) return false;
    return true;
  };

  for (const file of files) {
    if (!visible(file)) continue;
    const parts = file.path.split("/");
    let node = root;
    for (let i = 0; i < parts.length - 1; i += 1) {
      let directory = node.dirs.get(parts[i]);
      if (!directory) {
        directory = {
          name: parts[i],
          path: parts.slice(0, i + 1).join("/"),
          dirs: new Map(),
          files: [],
        };
        node.dirs.set(parts[i], directory);
      }
      node = directory;
    }
    node.files.push(file);
  }

  const fold = (directory: FileTreeDirectory): FileTreeDirectory => {
    let total = 0;
    let done = 0;
    for (const file of directory.files) {
      total += file.total;
      done += file.translated;
    }
    for (const child of directory.dirs.values()) {
      fold(child);
      total += child.total || 0;
      done += child.done || 0;
    }
    directory.total = total;
    directory.done = done;
    return directory;
  };

  const nameOf = (item: FileTreeRow): string =>
    item.type === "dir" ? item.dir.name : item.file.path;
  const progressOf = (item: FileTreeRow): number => {
    const total = item.type === "dir" ? item.dir.total || 0 : item.file.total;
    const done =
      item.type === "dir" ? item.dir.done || 0 : item.file.translated;
    return total ? done / total : 0;
  };
  const compare = (left: FileTreeRow, right: FileTreeRow): number => {
    if (sort === "progress") return progressOf(right) - progressOf(left);
    if (sort === "need") return right.need - left.need;
    return 0;
  };
  const sortRows = (rows: FileTreeRow[]): void => {
    rows.sort(
      (left, right) =>
        compare(left, right) || nameOf(left).localeCompare(nameOf(right)),
    );
  };

  const rows: FileTreeRow[] = [];
  const emit = (node: TreeNode, depth: number): void => {
    const children: FileTreeRow[] = [];
    for (const directory of node.dirs.values()) {
      fold(directory);
      children.push({
        type: "dir",
        depth,
        key: `d:${directory.path}`,
        dir: directory,
        open: Boolean(query || expanded[directory.path]),
        need: (directory.total || 0) - (directory.done || 0),
      });
    }
    for (const file of node.files) {
      children.push({
        type: "file",
        depth,
        key: `f:${file.path}`,
        file,
        need: file.total - file.translated,
      });
    }
    sortRows(children);
    for (const child of children) {
      rows.push(child);
      if (child.type === "dir" && child.open) emit(child.dir, depth + 1);
    }
  };

  emit(root, 0);
  return rows;
}

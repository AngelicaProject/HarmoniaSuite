#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

function u16(buffer, offset) {
  assert(offset >= 0 && offset + 2 <= buffer.length, `truncated PE data at ${offset}`);
  return buffer.readUInt16LE(offset);
}

function u32(buffer, offset) {
  assert(offset >= 0 && offset + 4 <= buffer.length, `truncated PE data at ${offset}`);
  return buffer.readUInt32LE(offset);
}

function parsePe(buffer) {
  assert(buffer.subarray(0, 2).toString("ascii") === "MZ", "not a Windows PE executable");
  const peOffset = u32(buffer, 0x3c);
  assert(buffer.subarray(peOffset, peOffset + 4).toString("ascii") === "PE\0\0", "invalid PE signature");

  const coff = peOffset + 4;
  const sectionCount = u16(buffer, coff + 2);
  const optionalSize = u16(buffer, coff + 16);
  const optional = coff + 20;
  const magic = u16(buffer, optional);
  const dataDirectoryOffset = magic === 0x20b ? 112 : magic === 0x10b ? 96 : null;
  assert(dataDirectoryOffset !== null, "unsupported PE optional header");

  const resourceDirectory = optional + dataDirectoryOffset + 16;
  const resourceRva = u32(buffer, resourceDirectory);
  const resourceSize = u32(buffer, resourceDirectory + 4);
  assert(resourceRva !== 0 && resourceSize !== 0, "PE has no resource directory");

  const sections = [];
  const sectionTable = optional + optionalSize;
  for (let index = 0; index < sectionCount; index += 1) {
    const section = sectionTable + index * 40;
    sections.push({
      virtualAddress: u32(buffer, section + 12),
      virtualSize: u32(buffer, section + 8),
      rawAddress: u32(buffer, section + 20),
      rawSize: u32(buffer, section + 16),
    });
  }

  function rvaToOffset(rva, length = 1) {
    const section = sections.find((candidate) => {
      const size = Math.max(candidate.virtualSize, candidate.rawSize);
      return rva >= candidate.virtualAddress && rva < candidate.virtualAddress + size;
    });
    assert(section, `RVA ${rva} is not in a PE section`);
    const offset = section.rawAddress + rva - section.virtualAddress;
    assert(offset >= 0 && offset + length <= buffer.length, `RVA ${rva} points outside the file`);
    return offset;
  }

  return {
    resourceRoot: rvaToOffset(resourceRva, Math.min(resourceSize, 1)),
    rvaToOffset,
  };
}

function resourceEntries(buffer, resourceRoot, relativeOffset) {
  const directory = resourceRoot + relativeOffset;
  const namedCount = u16(buffer, directory + 12);
  const idCount = u16(buffer, directory + 14);
  const count = namedCount + idCount;
  return Array.from({ length: count }, (_, index) => {
    const entry = directory + 16 + index * 8;
    const name = u32(buffer, entry);
    return { id: name & 0x80000000 ? null : name, offset: u32(buffer, entry + 4) };
  });
}

function resourceChild(buffer, resourceRoot, relativeOffset, id) {
  const entry = resourceEntries(buffer, resourceRoot, relativeOffset).find(
    (candidate) => candidate.id === id,
  );
  assert(entry, `missing resource directory ${id}`);
  assert(entry.offset & 0x80000000, `resource ${id} is not a directory`);
  return entry.offset & 0x7fffffff;
}

function firstResourceData(buffer, pe, relativeOffset) {
  const entry = resourceEntries(buffer, pe.resourceRoot, relativeOffset)[0];
  assert(entry, "resource directory is empty");
  const childOffset = entry.offset & 0x7fffffff;
  if (entry.offset & 0x80000000) return firstResourceData(buffer, pe, childOffset);

  const dataEntry = pe.resourceRoot + childOffset;
  const dataRva = u32(buffer, dataEntry);
  const size = u32(buffer, dataEntry + 4);
  const fileOffset = pe.rvaToOffset(dataRva, size);
  return buffer.subarray(fileOffset, fileOffset + size);
}

function resourceData(buffer, pe, typeId, nameId) {
  const typeDirectory = resourceChild(buffer, pe.resourceRoot, 0, typeId);
  const nameDirectory = resourceChild(buffer, pe.resourceRoot, typeDirectory, nameId);
  return firstResourceData(buffer, pe, nameDirectory);
}

function parseIco(buffer) {
  assert(u16(buffer, 0) === 0 && u16(buffer, 2) === 1, "not an ICO file");
  const count = u16(buffer, 4);
  assert(count > 0, "ICO file has no images");
  return Array.from({ length: count }, (_, index) => {
    const entry = 6 + index * 16;
    const size = u32(buffer, entry + 8);
    const offset = u32(buffer, entry + 12);
    assert(offset + size <= buffer.length, "ICO image points outside the file");
    return buffer.subarray(offset, offset + size);
  });
}

function hash(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

function verifyWindowsIcon(executable, icon) {
  const pe = parsePe(executable);
  const expectedImages = new Set(parseIco(icon).map(hash));
  const groupType = resourceChild(executable, pe.resourceRoot, 0, 14);
  const groupIcon = firstResourceData(executable, pe, groupType);
  assert(u16(groupIcon, 0) === 0 && u16(groupIcon, 2) === 1, "PE application icon group is invalid");
  const imageCount = u16(groupIcon, 4);
  assert(imageCount > 0, "PE application icon group has no images");

  for (let index = 0; index < imageCount; index += 1) {
    const entry = 6 + index * 14;
    const iconId = u16(groupIcon, entry + 12);
    const image = resourceData(executable, pe, 3, iconId);
    assert(expectedImages.has(hash(image)), "PE application icon does not match canonical ICO");
  }
}

async function main() {
  const [executablePath, iconPath] = process.argv.slice(2);
  if (!executablePath || !iconPath) {
    throw new Error("usage: verify-windows-icon.mjs <executable> <canonical-ico>");
  }
  verifyWindowsIcon(await readFile(executablePath), await readFile(iconPath));
  process.stdout.write(`Windows icon resource verified: ${executablePath}\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}

export { verifyWindowsIcon };

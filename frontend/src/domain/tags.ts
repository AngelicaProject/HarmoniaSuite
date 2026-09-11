import { UI_COLORS } from "./uiColors";

export interface TagSpan {
  text: string;
  start: number;
  end: number;
  anon?: boolean;
  inner?: string;
  skip?: boolean;
}

export interface AnonSpan extends TagSpan {
  inner: string;
}

export interface ValidationIssue {
  pos: number;
  len: number;
  message: string;
}

export type TagKind =
  "break" | "color" | "fmt" | "logic" | "value" | "media" | "misc" | "anon";

interface ColorState {
  fg: string | null;
  edge: string | null;
  shadow: string | null;
}

interface FormatState extends ColorState {
  italic: boolean;
  bold: boolean;
}

// Tag parser mirror of TagSupport.java. FFXIV Lumina macros as emitted by
// ReadOnlySeString.ToMacroString() (<br>, <colortype(504)>, <if(cond,a,b)>
// nestable, <payload: ...> for unknown codes). Names are MacroCode
// GetEncodeName() values, matched case-sensitively like Lumina's
// MacroStringParser. Backslash escapes the next char: \< is literal text.
function isNameChar(ch: string): boolean {
  return /[A-Za-z0-9_]/.test(ch);
}

function parseTagEnd(s: string, start: number): number {
  const n = s.length;
  let i = start + 1;
  const nameStart = i;
  while (i < n && isNameChar(s[i])) i++;
  if (i === nameStart || !/[A-Za-z]/.test(s[nameStart])) return -1;
  if (i < n && s[i] === "(") {
    let after;
    if (LOGIC_TAGS.has(s.slice(nameStart, i))) after = parseLogicArgs(s, i);
    else after = parseBalanced(s, i);
    if (after < 0) return -1;
    i = after;
  }
  return i < n && s[i] === ">" ? i + 1 : -1;
}

function parseLogicArgs(s: string, start: number): number {
  const n = s.length;
  let depth = 0,
    seenParen = false,
    i = start;
  while (i < n) {
    const c = s[i];
    if (c === "\\" && i + 1 < n) {
      i += 2;
      continue;
    }
    if (c === "<") {
      let end = parsePayloadEnd(s, i);
      if (end < 0) end = parseTagEnd(s, i);
      if (end > i) {
        i = end;
        continue;
      }
      i++;
      continue;
    }
    if (c === "(") {
      depth++;
      seenParen = true;
    } else if (c === ")") depth--;
    else if (c === ">" && depth <= 0 && (s[i - 1] === ")" || !seenParen))
      return i;
    i++;
  }
  return -1;
}

function parseBalanced(s: string, start: number): number {
  const n = s.length;
  let depth = 0,
    i = start;
  while (i < n) {
    const c = s[i];
    if (c === "\\" && i + 1 < n) {
      i += 2;
      continue;
    }
    if (c === "<") {
      const end = parseTagEnd(s, i);
      if (end > i) {
        i = end;
        continue;
      }
      i++;
      continue;
    }
    if (c === "(") depth++;
    else if (c === ")") {
      depth--;
      if (depth === 0) return i + 1;
    }
    i++;
  }
  return -1;
}

export function parseTags(value: string): TagSpan[] {
  const tags: TagSpan[] = [];
  if (!value) return tags;
  const n = value.length;
  let i = 0;
  while (i < n) {
    const c = value[i];
    if (c === "\\" && i + 1 < n) {
      i += 2;
      continue;
    }
    if (c === "<") {
      let end = parsePayloadEnd(value, i);
      if (end < 0) end = parseTagEnd(value, i);
      if (end > i) {
        tags.push({ text: value.slice(i, end), start: i, end });
        i = end;
        continue;
      }
    }
    i++;
  }
  return tags;
}

// Lumina emits `<payload: XX>` for unknown macro codes; opaque tag.
function parsePayloadEnd(s: string, start: number): number {
  if (!s.startsWith("<payload:", start)) return -1;
  const close = s.indexOf(">", start + 9);
  return close < 0 ? -1 : close + 1;
}

// Hidden speaker name: (-Name-), parsed by the game client as the dialogue
// nameplate (unknown speakers show (-???-)). Always at string start in game
// data, single per string; the inner may carry tags and parens but never a
// newline. The wrapper is engine syntax, the inner text is translated.
// Shape is open for more engine constructs of this kind later.
const ANON_TEXT = /[\p{L}\p{N}?？]/u;
const ANON_OPEN_NEXT = /[\p{L}\p{N}?？<"'"“”]/u;

export function parseAnon(value: string): AnonSpan[] {
  const out: AnonSpan[] = [];
  if (!value) return out;
  const n = value.length;
  let i = 0;
  while (i + 1 < n) {
    const c = value[i];
    if (c === "\\" && i + 1 < n) {
      i += 2;
      continue;
    }
    if (c === "(" && value[i + 1] === "-") {
      const end = anonEnd(value, i);
      if (end > i) {
        out.push({
          text: value.slice(i, end),
          inner: value.slice(i + 2, end - 2),
          start: i,
          end,
        });
        i = end;
        continue;
      }
    }
    i++;
  }
  return out;
}

// End (exclusive) of the (-...-) token starting at `start`, or -1. The first
// -) closes; the inner must be single-line and carry real text.
function anonEnd(s: string, start: number): number {
  const n = s.length;
  let i = start + 2;
  while (i + 1 < n) {
    const c = s[i];
    if (c === "\n" || c === "\r") return -1;
    if (c === "\\" && i + 1 < n) {
      i += 2;
      continue;
    }
    if (c === "-" && s[i + 1] === ")") {
      return ANON_TEXT.test(s.slice(start + 2, i)) ? i + 2 : -1;
    }
    i++;
  }
  return -1;
}

export function distinctAnon(value: string): string[] {
  const seen = new Set<string>(),
    out: string[] = [];
  for (const t of parseAnon(value)) {
    if (!seen.has(t.text)) {
      seen.add(t.text);
      out.push(t.text);
    }
  }
  return out;
}

// Hard errors for broken anonymizers (unclosed (-...-)). A bare (- is never
// enough: the opener must be followed by text-like input, so prose like
// (-: or (- ... stays quiet.
export function validateAnon(translation: string): ValidationIssue[] {
  const errors: ValidationIssue[] = [];
  if (!translation || !translation.trim()) return errors;
  const starts = new Set(parseAnon(translation).map((t) => t.start));
  for (let i = 0; i + 1 < translation.length; i++) {
    if (
      translation[i] === "(" &&
      translation[i + 1] === "-" &&
      !starts.has(i) &&
      !escapedAt(translation, i) &&
      ANON_OPEN_NEXT.test(translation[i + 2] || "")
    ) {
      const close = translation.indexOf("-)", i + 2);
      const len =
        close >= 0 && close - i <= 32
          ? close - i + 2
          : Math.min(24, translation.length - i);
      const end = Math.min(translation.length, i + 24);
      const snippet =
        translation.slice(i, end) + (end < translation.length ? "..." : "");
      errors.push({
        pos: i,
        len,
        message:
          "похоже на незакрытую конструкцию (-...-) (позиция " +
          i +
          "): " +
          shortTag(snippet),
      });
    }
  }
  return errors;
}

// Soft presence warnings: the inner is translated, so only the construct
// itself is compared — never its text.
export function warnAnon(source: string, translation: string): string[] {
  const warnings: string[] = [];
  if (!translation || !translation.trim()) return warnings;
  const want = parseAnon(source || "");
  const got = parseAnon(translation);
  for (let i = got.length; i < want.length; i++) {
    warnings.push(
      "нет конструкции " + shortTag(want[i].text) + " из оригинала",
    );
  }
  for (let i = want.length; i < got.length; i++) {
    warnings.push("новая конструкция " + shortTag(got[i].text));
  }
  return warnings;
}

export function tagSequence(value: string): string[] {
  return parseDeep(value).map((t) => t.text);
}

export function distinctTags(value: string): string[] {
  return [...new Set(parseDeep(value).map((t) => t.text))];
}

// All tags including nested ones (for validation); parseTags stays top-level
// for highlighting and span replacement.
export function parseDeep(value: string): TagSpan[] {
  const out: TagSpan[] = [];
  const walk = (s: string, base: number): void => {
    for (const t of parseTags(s)) {
      out.push({ text: t.text, start: t.start + base, end: t.end + base });
      if (t.end - t.start > 3)
        walk(s.slice(t.start + 1, t.end), base + t.start + 1);
    }
  };
  walk(value || "", 0);
  return out;
}

const LOGIC_TAGS = new Set([
  "if",
  "switch",
  "ifpcgender",
  "ifpcname",
  "ifself",
  "switchplatform",
]);

// Logic macros carry translated display text in branches: only name +
// condition (first argument) participate in set comparison.
export function normalizedTag(text: string): string {
  const paren = text.indexOf("(");
  const name = paren < 0 ? tagNameOf(text) : text.slice(1, paren);
  if (!LOGIC_TAGS.has(name) || paren < 0 || !text.endsWith(">")) return text;
  let depth = 0,
    brackets = 0;
  for (let i = paren; i < text.length - 1; i++) {
    const c = text[i];
    if (c === "\\" && i + 1 < text.length - 1) {
      i++;
      continue;
    }
    if (c === "<") {
      let end = parsePayloadEnd(text, i);
      if (end < 0) end = parseTagEnd(text, i);
      if (end > i) {
        i = end - 1;
        continue;
      }
    }
    if (c === "[") brackets++;
    else if (c === "]") {
      if (brackets > 0) brackets--;
    } else if (c === "(") depth++;
    else if (c === ")") depth--;
    else if (c === "," && depth === 1 && brackets === 0)
      return "<" + name + text.slice(paren, i) + ")>";
  }
  return logicConditionGroups(text, name, paren);
}

// Fallback when no top-level comma splits head from branches (composite
// conditions like <if(g1),if(g2),branches...>): leading balanced groups.
function logicConditionGroups(
  text: string,
  name: string,
  paren: number,
): string {
  let groups = "",
    i = paren;
  const n = text.length - 1;
  while (i < n) {
    const c = text[i];
    if (c === "," || c === " " || c === "\t") {
      i++;
      continue;
    }
    if (c === "<" || c === ">" || c === ")") break;
    const end = scanConditionGroup(text, i, n);
    if (end < 0) break;
    groups += text.slice(i, end);
    i = end;
  }
  return groups ? "<" + name + groups + ">" : text;
}

function scanConditionGroup(text: string, i: number, n: number): number {
  let j = i;
  while (j < n && /[A-Za-z0-9_]/.test(text[j])) j++;
  if (j >= n || text[j] !== "(") return -1;
  let depth = 0,
    k = j;
  while (k < n) {
    const c = text[k];
    if (c === "\\" && k + 1 < n) {
      k += 2;
      continue;
    }
    if (c === "<") return -1;
    if (c === "(") depth++;
    else if (c === ")") {
      depth--;
      if (depth === 0) return k + 1;
    }
    k++;
  }
  return -1;
}

function shortTag(text: string): string {
  return text.length > 80 ? text.slice(0, 80) + "..." : text;
}

function escapedAt(s: string, pos: number): boolean {
  let bs = 0;
  for (let i = pos - 1; i >= 0 && s[i] === "\\"; i--) bs++;
  return bs % 2 === 1;
}

export function validateTags(
  source: string,
  translation: string | null | undefined,
): ValidationIssue[] {
  const errors: ValidationIssue[] = [];
  if (!translation || !translation.trim()) return errors;
  const starts = new Set(parseDeep(translation).map((t) => t.start));
  for (let i = 0; i + 1 < translation.length; i++) {
    if (
      translation[i] === "<" &&
      /[A-Za-z]/.test(translation[i + 1]) &&
      !starts.has(i) &&
      !escapedAt(translation, i)
    ) {
      const end = Math.min(translation.length, i + 24);
      const snippet =
        translation.slice(i, end) + (end < translation.length ? "..." : "");
      const close = translation.indexOf(">", i);
      const len =
        close >= 0 && close - i <= 30
          ? close - i + 1
          : Math.min(24, translation.length - i);
      errors.push({
        pos: i,
        len,
        message:
          "похоже на незакрытый тег (позиция " + i + "): " + shortTag(snippet),
      });
    }
  }
  for (const e of validateAnon(translation)) errors.push(e);
  return errors;
}

// Normalized keys of source tags missing in translation (for UI marking).
export function findMissingTags(source: string, translation: string): string[] {
  if (!translation || !translation.trim()) return [];
  const want = tagSequence(source || "").map(normalizedTag);
  const have = new Map<string, number>();
  tagSequence(translation)
    .map(normalizedTag)
    .forEach((t) => have.set(t, (have.get(t) || 0) + 1));
  const missing: string[] = [];
  const seen = new Set<string>();
  for (const t of want) {
    if (seen.has(t)) continue;
    seen.add(t);
    const need = want.filter((x) => x === t).length - (have.get(t) || 0);
    if (need > 0) missing.push(t);
  }
  const wantAnon = parseAnon(source || "");
  if (wantAnon.length > parseAnon(translation).length) {
    for (const t of wantAnon)
      if (!missing.includes(t.text)) missing.push(t.text);
  }
  return missing;
}

// Soft warnings: missing original tags or newly added ones. Never block saving:
// translators may add tags the game understands, order may follow target grammar.
export function warnTags(source: string, translation: string): string[] {
  const warnings: string[] = [];
  if (!translation || !translation.trim()) return warnings;
  const want = tagSequence(source || "").map(normalizedTag);
  const got = tagSequence(translation).map(normalizedTag);
  if (want.join("\0") === got.join("\0")) return warnAnon(source, translation);
  const count = (list: string[]): Map<string, number> => {
    const m = new Map<string, number>();
    list.forEach((t) => m.set(t, (m.get(t) || 0) + 1));
    return m;
  };
  const w = count(want),
    g = count(got);
  const times = (c: number): string => (c > 1 ? " (x" + c + ")" : "");
  for (const [t, c] of w) {
    const missing = c - (g.get(t) || 0);
    if (missing > 0)
      warnings.push(
        "нет тега " + shortTag(t) + " из оригинала" + times(missing),
      );
  }
  for (const [t, c] of g) {
    const extra = c - (w.get(t) || 0);
    if (extra > 0) warnings.push("новый тег " + shortTag(t) + times(extra));
  }
  return warnings.concat(warnAnon(source, translation));
}

// Tag kinds follow Lumina's MacroCode (src/Lumina/Text/Payloads/MacroCode.cs):
// names below are MacroCode.GetEncodeName() values.
const TAG_KINDS: Record<Exclude<TagKind, "misc" | "anon">, string[]> = {
  break: ["br"],
  color: [
    "colortype",
    "edgecolortype",
    "color",
    "edgecolor",
    "shadowcolor",
    "edge",
    "shadow",
  ],
  fmt: ["italic", "bold", "ruby"],
  logic: ["if", "switch", "ifpcgender", "ifpcname", "ifself", "switchplatform"],
  value: [
    "num",
    "hex",
    "kilo",
    "byte",
    "sec",
    "time",
    "float",
    "digit",
    "ordinal",
    "sheet",
    "sheetsub",
    "string",
    "caps",
    "head",
    "headall",
    "lower",
    "lowerhead",
    "janoun",
    "ennoun",
    "denoun",
    "frnoun",
    "chnoun",
    "josa",
    "josaro",
    "pcname",
    "levelpos",
  ],
  media: ["icon", "icon2", "sound", "settime", "setresettime"],
};
// Undocumented MacroCodes (key, link, split, fixed, scale, wait) fall into misc.
const KIND_LABELS: Record<TagKind, string> = {
  break: "Перенос строки",
  color: "Цвет",
  fmt: "Форматирование",
  logic: "Условие",
  value: "Подстановка",
  media: "Иконка/время",
  misc: "Тег",
  anon: "Скрытое имя",
};

export function tagNameOf(text: string): string {
  const m = /^<([A-Za-z][A-Za-z0-9_]*)/.exec(text || "");
  return m ? m[1].toLowerCase() : "";
}

export function tagKindOf(text: string): TagKind {
  if (isAnonToken(text)) return "anon";
  const name = tagNameOf(text);
  for (const kind of Object.keys(TAG_KINDS) as Array<keyof typeof TAG_KINDS>) {
    if (TAG_KINDS[kind].includes(name)) return kind;
  }
  return "misc";
}

// Whole-string (-...-) token, parsed — never matched loosely.
function isAnonToken(text: string): boolean {
  if (!text || !text.startsWith("(-") || !text.endsWith("-)")) return false;
  const found = parseAnon(text);
  return (
    found.length === 1 && found[0].start === 0 && found[0].end === text.length
  );
}

export function tagKindLabel(kind: TagKind): string {
  return KIND_LABELS[kind] || KIND_LABELS.misc;
}

// Tracks color state for the in-game preview. colortype/edgecolortype take a
// UIColor sheet row (0 and stackcolor reset); color/edgecolor/shadowcolor take
// a raw 0xAARRGGBB value or stackcolor. Unknown ids keep the current state.
function decodeArgb(n: number): string {
  const a = (n >>> 24) & 255,
    r = (n >>> 16) & 255,
    g = (n >>> 8) & 255,
    b = n & 255;
  return a === 255
    ? "#" + [r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("")
    : "rgba(" + r + "," + g + "," + b + "," + (a / 255).toFixed(3) + ")";
}

function trackColor(tag: string, state: ColorState): void {
  const colorTypeMatch = /^<(edge)?colortype\(([^)]*)\)>$/.exec(tag);
  if (colorTypeMatch) {
    const set = (v: string | null): void => {
      if (colorTypeMatch[1]) state.edge = v;
      else state.fg = v;
    };
    if (colorTypeMatch[2] === "0" || colorTypeMatch[2] === "stackcolor") {
      set(null);
      return;
    }
    if (
      /^\d+$/.test(colorTypeMatch[2]) &&
      (UI_COLORS as Record<string, string>)[colorTypeMatch[2]] != null
    )
      set((UI_COLORS as Record<string, string>)[colorTypeMatch[2]]);
    return;
  }
  const rawColorMatch = /^<(edge|shadow)?color\(([^)]*)\)>$/.exec(tag);
  if (rawColorMatch) {
    const set = (v: string | null): void => {
      if (rawColorMatch[1] === "edge") state.edge = v;
      else if (rawColorMatch[1] === "shadow") state.shadow = v;
      else state.fg = v;
    };
    if (rawColorMatch[2] === "0" || rawColorMatch[2] === "stackcolor") {
      set(null);
      return;
    }
    if (/^\d+$/.test(rawColorMatch[2]))
      set(decodeArgb(Number(rawColorMatch[2])));
  }
}

function escAttr(s: string): string {
  return String(s).replace(/&/g, "&amp;").replace(/"/g, "&quot;");
}
function escHtml(s: string): string {
  return String(s).replace(
    /[&<>]/g,
    (c) =>
      (({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }) as Record<string, string>)[
        c
      ],
  );
}

function coloredText(state: ColorState, html: string): string {
  if (!state.fg && !state.edge && !state.shadow) return html;
  const shadows: string[] = [];
  if (state.edge)
    shadows.push(
      "1px 0 0 " + state.edge,
      "-1px 0 0 " + state.edge,
      "0 1px 0 " + state.edge,
      "0 -1px 0 " + state.edge,
    );
  if (state.shadow) shadows.push("2px 2px 0 " + state.shadow);
  let style = "";
  if (state.fg) style += "color:" + escAttr(state.fg) + ";";
  if (shadows.length)
    style += "text-shadow:" + escAttr(shadows.join(",")) + ";";
  return '<span style="' + style + '">' + html + "</span>";
}

function styledText(state: FormatState, html: string): string {
  let out = coloredText(state, html);
  if (state.bold) out = "<b>" + out + "</b>";
  if (state.italic) out = "<i>" + out + "</i>";
  return out;
}

// italic/bold are on/off toggles consumed by the preview, not markers.
function trackFmt(tag: string, state: FormatState): boolean {
  const m = /^<(italic|bold)\(([^)]*)\)>$/.exec(tag);
  if (!m) return false;
  const key = m[1] as "italic" | "bold";
  state[key] = m[2].trim() !== "0";
  return true;
}

function splitArgs(inner: string): string[] {
  const parts: string[] = [];
  let depth = 0,
    cur = "";
  for (let i = 0; i < inner.length; i++) {
    const c = inner[i];
    if (c === "<") {
      const end =
        parsePayloadEnd(inner, i) >= 0
          ? parsePayloadEnd(inner, i)
          : parseTagEnd(inner, i);
      if (end > i) {
        cur += inner.slice(i, end);
        i = end - 1;
        continue;
      }
    }
    if (c === "(") depth++;
    else if (c === ")") depth--;
    if (c === "," && depth === 0) {
      parts.push(cur);
      cur = "";
      continue;
    }
    cur += c;
  }
  parts.push(cur);
  return parts;
}

function parseRuby(text: string): { base: string; reading: string } | null {
  if (!/^<ruby\(/.test(text) || !text.endsWith(")>")) return null;
  const parts = splitArgs(text.slice(6, -2));
  if (parts.length < 2) return null;
  return { base: parts[0], reading: parts.slice(1).join(",") };
}

// Top-level spans for highlighting: tags and (-...-) anonymizers merged,
// nested ones left for the recursive render (anonymizer inners may carry tags).
function topSpans(str: string): TagSpan[] {
  const spans = parseTags(str).map((t) => ({
    text: t.text,
    start: t.start,
    end: t.end,
    anon: false,
  }));
  for (const t of parseAnon(str))
    spans.push({ text: t.text, start: t.start, end: t.end, anon: true });
  spans.sort((a, b) => a.start - b.start || b.end - a.end);
  const out: TagSpan[] = [];
  let lastEnd = -1;
  for (const t of spans) {
    if (t.start < lastEnd) continue;
    out.push(t);
    lastEnd = t.end;
  }
  return out;
}

// Single-pass highlight: plain text escaped (tinted by active <colortype>),
// tags wrapped in pills. Logic macros (if/switch/...) nest: the wrapper pill
// holds branch text and nested pills. missingKeys (normalized) get flagged.
// Validation and repair keep the whole tag.
export function highlightTags(
  value: string,
  missingKeys: string[] = [],
): string {
  if (!value) return "";
  const missing = new Set(missingKeys || []);
  const state: ColorState = { fg: null, edge: null, shadow: null };
  let idx = 0;
  const renderSlice = (str: string): string => {
    let out = "",
      pos = 0;
    for (const t of topSpans(str)) {
      const chunk = str.slice(pos, t.start);
      if (chunk) out += coloredText(state, escHtml(chunk));
      if (t.anon) {
        const missed = missing.has(t.text) ? " tk-missing" : "";
        out +=
          '<mark class="ptoken tk-anon' +
          missed +
          '" data-tag="' +
          idx++ +
          '" title="' +
          escHtml(tagKindLabel("anon") + ": " + t.text) +
          '">' +
          renderSlice(str.slice(t.start + 2, t.end - 2)) +
          "</mark>";
      } else {
        trackColor(t.text, state);
        const kind = tagKindOf(t.text);
        const missed = missing.has(normalizedTag(t.text)) ? " tk-missing" : "";
        let body = escHtml(t.text);
        if (LOGIC_TAGS.has(tagNameOf(t.text))) {
          const sub = str.slice(t.start + 1, t.end);
          if (parseTags(sub).length || parseAnon(sub).length)
            body = renderSlice(sub);
        }
        out +=
          '<mark class="ptoken tk-' +
          kind +
          missed +
          '" data-tag="' +
          idx++ +
          '" title="' +
          escHtml(tagKindLabel(kind) + ": " + t.text) +
          '">' +
          body +
          "</mark>";
      }
      pos = t.end;
    }
    const tail = str.slice(pos);
    if (tail) out += coloredText(state, escHtml(tail));
    return out;
  };
  return renderSlice(value);
}

// Game-like preview: colors and line breaks applied, color macros hidden,
// other macros as ghost markers. marks=[{pos,len}] get error underlines.
// Conditions/sheet values can't be resolved without game state: approximation.
// End offset (in tag coords) just past a logic head `<name(conds),` or the
// leading balanced condition groups of a composite `<if(g1),if(g2),...>`;
// -1 when the head shape is unknown (caller keeps old zero-width ghost).
function logicHeadEnd(text: string): number {
  const m = /^<([A-Za-z][A-Za-z0-9_]*)\(/.exec(text);
  if (!m || !LOGIC_TAGS.has(m[1]) || !text.endsWith(">")) return -1;
  const paren = m[0].length - 1;
  let depth = 0,
    brackets = 0;
  for (let i = paren; i < text.length - 1; i++) {
    const c = text[i];
    if (c === "\\" && i + 1 < text.length - 1) {
      i++;
      continue;
    }
    if (c === "<") {
      let end = parsePayloadEnd(text, i);
      if (end < 0) end = parseTagEnd(text, i);
      if (end > i) {
        i = end - 1;
        continue;
      }
    }
    if (c === "[") brackets++;
    else if (c === "]") {
      if (brackets > 0) brackets--;
    } else if (c === "(") depth++;
    else if (c === ")") depth--;
    else if (c === "," && depth === 1 && brackets === 0) return i + 1;
  }
  let i = paren;
  const n = text.length - 1;
  let groups = 0;
  while (i < n) {
    const c = text[i];
    if (c === "," || c === " " || c === "\t") {
      i++;
      continue;
    }
    if (c === "<" || c === ">" || c === ")") break;
    const end = scanConditionGroup(text, i, n);
    if (end < 0) break;
    groups++;
    i = end;
  }
  if (!groups) return -1;
  if (text[i] === ",") i++;
  return i;
}

// Start offset (in tag coords) of trailing closer syntax (`,,)>`); branch
// text before it is kept. -1 when the tail carries anything else.
function logicTailStart(text: string): number {
  if (!text.endsWith(">")) return -1;
  let i = text.length - 2;
  while (
    i > 0 &&
    (text[i] === "," || text[i] === ")" || text[i] === " " || text[i] === "\t")
  )
    i--;
  const start = i + 1;
  if (start >= text.length - 1 || !text.slice(start, -1).includes(")"))
    return -1;
  return start;
}

// Flat tag list for the game preview: logic wrappers become ghost entries
// consuming the head (`<if(cond),`), nested tags flow through normally, and
// a skip entry consumes the tail closers (`,,)>`). Heads/tails are macro
// syntax, never game text; branch text stays visible between them.
function previewTags(value: string, tags: TagSpan[]): TagSpan[] {
  const out: TagSpan[] = [];
  for (const t of tags) {
    if (LOGIC_TAGS.has(tagNameOf(t.text))) {
      const inner = parseTags(value.slice(t.start + 1, t.end)).map((x) => ({
        text: x.text,
        start: x.start + t.start + 1,
        end: x.end + t.start + 1,
      }));
      if (inner.length) {
        const expanded: TagSpan[] = [];
        for (const nt of previewTags(value, inner)) expanded.push(nt);
        const head = logicHeadEnd(t.text);
        const headAbs = head > 0 ? t.start + head : t.start;
        out.push({ text: t.text, start: t.start, end: headAbs });
        for (const e of expanded) out.push(e);
        const lastEnd = expanded.length
          ? expanded[expanded.length - 1].end
          : headAbs;
        const tail = logicTailStart(t.text);
        // Clamp into pure-syntax suffix: overlap means the run reaches into
        // consumed spans, so everything from the cursor on is closers.
        const tailAbs = tail > 0 ? Math.max(t.start + tail, lastEnd) : -1;
        if (tailAbs >= lastEnd && tailAbs < t.end && t.end > lastEnd)
          out.push({ text: "", start: tailAbs, end: t.end, skip: true });
        continue;
      }
    }
    out.push(t);
  }
  return out;
}

// Plate label: anonymizer inner without macro syntax (view-only).
function stripTopTags(s: string): string {
  let out = "",
    pos = 0;
  for (const t of parseTags(s)) {
    out += s.slice(pos, t.start);
    pos = t.end;
  }
  return out + s.slice(pos);
}

export function renderGamePreview(
  value: string,
  marks: Array<{ pos: number; len: number }> = [],
): string {
  if (!value) return "";
  const anons = parseAnon(value);
  const inAnon = (s: number, e: number): boolean =>
    anons.some((a) => s >= a.start && e <= a.end);
  const tags: TagSpan[] = previewTags(
    value,
    parseTags(value).filter((t) => !inAnon(t.start, t.end)),
  );
  for (const a of anons)
    tags.push({
      text: a.text,
      start: a.start,
      end: a.end,
      anon: true,
      inner: a.inner,
    });
  tags.sort((a, b) => a.start - b.start);
  const state: FormatState = {
    fg: null,
    edge: null,
    shadow: null,
    italic: false,
    bold: false,
  };
  const ranges = (marks || [])
    .map((m) => ({ from: m.pos, to: m.pos + m.len }))
    .sort((a, b) => a.from - b.from);
  let out = "",
    pos = 0;
  const pushText = (chunk: string, base: number): void => {
    if (!chunk) return;
    const segs: Array<{ t: string; bad: boolean; at: number }> = [];
    let p = 0;
    for (const r of ranges) {
      const s = Math.max(r.from, base) - base,
        e = Math.min(r.to, base + chunk.length) - base;
      if (e <= p || s >= chunk.length) continue;
      if (s > p) segs.push({ t: chunk.slice(p, s), bad: false, at: 0 });
      const from = Math.max(s, p);
      segs.push({ t: chunk.slice(from, e), bad: true, at: base + from });
      p = Math.max(p, e);
    }
    if (p < chunk.length) segs.push({ t: chunk.slice(p), bad: false, at: 0 });
    for (const s of segs) {
      const html = escHtml(s.t.replace(/\\(.)/g, "$1"));
      out += s.bad
        ? '<span class="tag-err" data-err="' +
          s.at +
          '" title="Битый тег — клик: к месту в тексте">' +
          html +
          "</span>"
        : styledText(state, html);
    }
  };
  tags.forEach((t) => {
    if (t.start < pos) return;
    pushText(value.slice(pos, t.start), pos);
    if (t.skip) {
      pos = t.end;
      return;
    }
    if (t.anon) {
      const inner = t.inner || "";
      const label =
        stripTopTags(inner).replace(/\\(.)/g, "$1") ||
        inner.replace(/\\(.)/g, "$1");
      out +=
        '<span class="anplate" title="' +
        escHtml("Скрытое имя: " + t.text) +
        '">' +
        escHtml(label) +
        "</span>";
      pos = t.end;
      return;
    }
    trackColor(t.text, state);
    const ruby = parseRuby(t.text);
    if (ruby) {
      out +=
        "<ruby>" +
        styledText(state, escHtml(ruby.base)) +
        "<rt>" +
        escHtml(ruby.reading) +
        "</rt></ruby>";
    } else if (trackFmt(t.text, state)) {
      // consumed toggle, no marker
    } else if (t.text === "<br>") out += "<br>";
    else if (t.text === "<nbsp>") out += "&nbsp;";
    else if (t.text === "<-->") out += "–";
    else if (t.text === "<->") out += "­";
    else if (tagKindOf(t.text) !== "color") {
      out +=
        '<span class="gx" title="' +
        escHtml(t.text) +
        '">' +
        escHtml(tagNameOf(t.text) || "tag") +
        "</span>";
    }
    pos = t.end;
  });
  pushText(value.slice(pos), pos);
  return out;
}

// Frontend tag-parser tests (mirror of TagSupportTest). Zero deps, node built-in runner.
// Runs with the frontend Vitest suite.
import { describe, it, expect } from "vitest";
import {
  parseTags,
  parseDeep,
  parseAnon,
  distinctAnon,
  tagSequence,
  distinctTags,
  tagNameOf,
  tagKindOf,
  tagKindLabel,
  validateTags,
  validateAnon,
  warnTags,
  warnAnon,
  findMissingTags,
  normalizedTag,
  highlightTags,
  renderGamePreview,
} from "./tags";
import type { TagSpan } from "./tags";

const assert = {
  deepEqual: (actual: unknown, expected: unknown) =>
    expect(actual).toEqual(expected),
  equal: (actual: unknown, expected: unknown) =>
    expect(actual).toEqual(expected),
  ok: (value: unknown) => expect(value).toBeTruthy(),
  match: (value: string, pattern: RegExp) => expect(value).toMatch(pattern),
};

const texts = (list: TagSpan[]): string[] => list.map((t) => t.text);

describe("parse", () => {
  it("извлекает простые теги по порядку", () => {
    assert.deepEqual(texts(parseTags("A<br>B<colortype(504)>C")), [
      "<br>",
      "<colortype(504)>",
    ]);
  });
  it("не разрывается на сравнении внутри условия", () => {
    assert.deepEqual(texts(parseTags("Potency <if([gnum72>=94],220,150)>.")), [
      "<if([gnum72>=94],220,150)>",
    ]);
  });
  it("вложенные теги — один внешний", () => {
    const outer = "<if([gnum68==19],<if([gnum72>=84],200,150)>,150)>";
    assert.deepEqual(texts(parseTags("Deal " + outer + " damage.")), [outer]);
  });
  it("экранированный тег текстом не считается", () => {
    assert.deepEqual(parseTags(String.raw`Say \<sigh> now`), []);
    assert.deepEqual(texts(parseTags(String.raw`A\<sigh><br>`)), ["<br>"]);
  });
  it("незакрытые скобки тегом не считаются", () => {
    assert.deepEqual(parseTags("Broken <colortype(504> text"), []);
    assert.deepEqual(parseTags("Plain a < b comparison"), []);
  });
  it("payload-тег непрозрачный", () => {
    assert.deepEqual(texts(parseTags("A<payload: 02>B")), ["<payload: 02>"]);
  });
});

describe("kind labels", () => {
  it("labels ready kinds without parsing them as tag text", () => {
    expect(tagKindLabel("break")).toBe("Перенос строки");
    expect(tagKindLabel("color")).toBe("Цвет");
    expect(tagKindLabel("misc")).toBe("Тег");
  });
});

describe("deep", () => {
  it("находит вложенные теги", () => {
    assert.deepEqual(texts(parseDeep("A<if(X,<br>)>B")), [
      "<if(X,<br>)>",
      "<br>",
    ]);
  });
  it("distinct без повторов", () => {
    assert.deepEqual(distinctTags("A<br>B<colortype(0)>C<br>"), [
      "<br>",
      "<colortype(0)>",
    ]);
  });
});

describe("validate", () => {
  it("пропускает совпадающие и пустые", () => {
    assert.deepEqual(validateTags("A<br>", "А<br>"), []);
    assert.deepEqual(validateTags("A<br>", ""), []);
    assert.deepEqual(validateTags("A<br>", null), []);
  });
  it("вложенные теги не считаются незакрытыми", () => {
    assert.deepEqual(
      validateTags(
        "Yes<br>No<if([gnum1==2],<br>Yes)>",
        "Да<br>Нет<if([gnum1==2],<br>Да)>",
      ),
      [],
    );
  });
  it("одна опечатка — одна ошибка с позицией и длиной", () => {
    const errs = validateTags(
      "Grants <colortype(506)>X!",
      "Дарует <colortype(506)>Бред<edgecolortype:507>!",
    );
    assert.equal(errs.length, 1);
    assert.equal(errs[0].pos, 27);
    assert.ok(errs[0].len > 10);
    assert.match(errs[0].message, /позиция 27/);
  });
});

describe("warnings", () => {
  it("переведённые ветки if тихие", () => {
    assert.deepEqual(
      warnTags("A<if([gnum1==2],Yes,No)>B", "А<if([gnum1==2],Да,Нет)>Б"),
      [],
    );
  });
  it("находят отсутствующие и новые", () => {
    const w = warnTags(
      "A<colortype(506)>B<edgecolortype(507)>",
      "А<colortype(506)>Б<br>",
    );
    assert.equal(w.length, 2);
    assert.match(w[0], /нет тега <edgecolortype\(507\)>/);
    assert.match(w[1], /новый тег <br>/);
  });
  it("изменённое условие if видно", () => {
    const w = warnTags("A<if([gnum1==2],Yes,No)>", "А<if([gnum1==3],Да,Нет)>");
    assert.equal(w.length, 2);
    assert.match(w[0], /<if\(\[gnum1==2\]\)>/);
  });
  it("порядок и пустой перевод не шумят", () => {
    assert.deepEqual(
      warnTags("A<br>B<colortype(0)>", "А<colortype(0)>Б<br>"),
      [],
    );
    assert.deepEqual(warnTags("A<br>", ""), []);
  });
});

describe("kinds", () => {
  it("категории по Lumina MacroCode", () => {
    assert.equal(tagKindOf("<br>"), "break");
    assert.equal(tagKindOf("<COLORTYPE(504)>"), "color");
    assert.equal(tagKindOf("<ifpcgender(x,m,f)>"), "logic");
    assert.equal(tagKindOf("<num(x)>"), "value");
    assert.equal(tagKindOf("<icon(5)>"), "media");
    assert.equal(tagKindOf("<italic(1)>"), "fmt");
    assert.equal(tagKindOf("<whatever>"), "misc");
  });
  it("имена тегов", () => {
    assert.equal(tagNameOf("<colortype(506)>"), "colortype");
    assert.equal(tagNameOf("<payload: 02>"), "payload");
  });
  it("нормализация логики", () => {
    assert.equal(normalizedTag("<if([gnum1==2],Yes,No)>"), "<if([gnum1==2])>");
    assert.equal(normalizedTag("<br>"), "<br>");
  });
  it("missing keys", () => {
    assert.deepEqual(findMissingTags("A<br>B", "А<br>"), []);
    assert.deepEqual(findMissingTags("A<br>B", "А"), ["<br>"]);
  });
});

describe("render", () => {
  it("подсветка без вложенных mark", () => {
    const h = highlightTags("A<br>B<br>".repeat(20));
    assert.equal(h.match(/<mark/g)?.length ?? 0, 40);
    assert.ok(!h.includes('<mark class="ptoken"><mark'));
  });
  it("классы категорий и тултипы", () => {
    const h = highlightTags("A<br>B");
    assert.ok(h.includes("tk-break"));
    assert.ok(h.includes("Перенос строки"));
  });
  it("флаг пропущенных", () => {
    const h = highlightTags("A<br>B", ["<br>"]);
    assert.ok(h.includes("tk-missing"));
  });
  it("превью красит и прячет цветные теги", () => {
    const p = renderGamePreview("A<colortype(506)>B<edgecolortype(0)>C<br>D");
    assert.ok(p.includes("color:#"));
    assert.ok(!p.includes("colortype"));
    assert.ok(p.includes("<br>"));
  });
  it("превью метит битые места", () => {
    const tr = "X<edgecolortype:507>Y";
    const p = renderGamePreview(tr, validateTags("", tr));
    assert.ok(p.includes("tag-err"));
    assert.ok(p.includes('data-err="1"'));
  });
});

describe("anon", () => {
  const game =
    "(-Polite Hyuran Male-)I have come, <if(gnum4,Mistress,Master)>.";
  it("парсит скрытое имя в начале строки", () => {
    const a = parseAnon(game);
    assert.equal(a.length, 1);
    assert.equal(a[0].text, "(-Polite Hyuran Male-)");
    assert.equal(a[0].inner, "Polite Hyuran Male");
    assert.equal(a[0].start, 0);
  });
  it("теги рядом целы, distinct идёт первым чипом", () => {
    assert.deepEqual(tagSequence(game), ["<if(gnum4,Mistress,Master)>"]);
    assert.deepEqual(distinctAnon(game), ["(-Polite Hyuran Male-)"]);
  });
  it("внутренность с тегами и скобками", () => {
    const v = "(-<italic(1)>Title (Revision 34)<italic(0)>-)Body";
    assert.equal(
      parseAnon(v)[0].text,
      "(-<italic(1)>Title (Revision 34)<italic(0)>-)",
    );
    assert.equal(parseTags(v).length, 2);
  });
  it("экраны и скобки без текста не токены", () => {
    assert.deepEqual(parseAnon(String.raw`Say \(-hi-) now`), []);
    assert.deepEqual(parseAnon("(- -) and (...) and (-...-)"), []);
  });
  it("незакрытая конструкция — ошибка с позицией", () => {
    const errs = validateTags("", "(-Mikoto, hello");
    assert.equal(errs.length, 1);
    assert.equal(errs[0].pos, 0);
    assert.match(errs[0].message, /незакрытую конструкцию/);
    assert.deepEqual(validateAnon("Say (-5 hi").length, 1);
  });
  it("проза со скобками тихая", () => {
    assert.deepEqual(validateTags("", "Smile (-: bye"), []);
    assert.deepEqual(validateTags("", "a (- b"), []);
    assert.deepEqual(validateTags("", "(-???-)Hello"), []);
  });
  it("предупреждения по наличию, перевод внутренности тих", () => {
    assert.match(
      warnTags("(-Narrator-)Hello", "Привет")[0],
      /нет конструкции \(-Narrator-\)/,
    );
    assert.match(
      warnTags("Hello", "(-Рассказчик-)Привет")[0],
      /новая конструкция/,
    );
    assert.deepEqual(warnTags("(-Narrator-)Hello", "(-Рассказчик-)Привет"), []);
    assert.deepEqual(warnAnon("A", ""), []);
  });
  it("missing подсвечивает исходный чип", () => {
    assert.deepEqual(findMissingTags("(-Mikoto-)Hi", "Привет"), ["(-Mikoto-)"]);
    assert.deepEqual(findMissingTags("(-A-)x", "(-Б-)у"), []);
  });
  it("вид — пилюля скрытого имени", () => {
    assert.equal(tagKindOf("(-Mikoto-)"), "anon");
    assert.equal(tagKindLabel("anon"), "Скрытое имя");
    const h = highlightTags(game, ["(-Polite Hyuran Male-)"]);
    assert.ok(h.includes("tk-anon"));
    assert.ok(h.includes("tk-missing"));
    assert.ok(h.includes("Скрытое имя"));
  });
  it("превью — плашка имени плюс тело", () => {
    const p = renderGamePreview(game);
    assert.ok(p.includes('<span class="anplate"'));
    assert.ok(p.includes("Polite Hyuran Male"));
    assert.equal(p.split("(-Polite").length, 2);
    assert.ok(p.includes("I have come"));
  });
  it("превью чистит макросы из плашки", () => {
    const p = renderGamePreview("(-<italic(1)>Title<italic(0)>-)Body");
    assert.ok(!p.includes("<italic"));
    assert.ok(p.includes("Title"));
  });
});

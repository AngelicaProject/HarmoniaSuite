// Frontend tag-parser tests (mirror of TagSupportTest). Zero deps, node built-in runner.
// Usage: node --test tools/test-tags.mjs   (run from the repo root)
import {describe, it} from 'node:test';
import assert from 'node:assert/strict';
import {
  parseTags, parseDeep, tagSequence, distinctTags, tagNameOf, tagKindOf,
  validateTags, warnTags, findMissingTags, normalizedTag,
  highlightTags, renderGamePreview,
} from '../src/main/resources/static/js/tags.js';

const texts = list => list.map(t => t.text);

describe('parse', () => {
  it('извлекает простые теги по порядку', () => {
    assert.deepEqual(texts(parseTags('A<br>B<colortype(504)>C')), ['<br>', '<colortype(504)>']);
  });
  it('не разрывается на сравнении внутри условия', () => {
    assert.deepEqual(texts(parseTags('Potency <if([gnum72>=94],220,150)>.')), ['<if([gnum72>=94],220,150)>']);
  });
  it('вложенные теги — один внешний', () => {
    const outer = '<if([gnum68==19],<if([gnum72>=84],200,150)>,150)>';
    assert.deepEqual(texts(parseTags('Deal ' + outer + ' damage.')), [outer]);
  });
  it('экранированный тег текстом не считается', () => {
    assert.deepEqual(parseTags(String.raw`Say \<sigh> now`), []);
    assert.deepEqual(texts(parseTags(String.raw`A\<sigh><br>`)), ['<br>']);
  });
  it('незакрытые скобки тегом не считаются', () => {
    assert.deepEqual(parseTags('Broken <colortype(504> text'), []);
    assert.deepEqual(parseTags('Plain a < b comparison'), []);
  });
  it('payload-тег непрозрачный', () => {
    assert.deepEqual(texts(parseTags('A<payload: 02>B')), ['<payload: 02>']);
  });
});

describe('deep', () => {
  it('находит вложенные теги', () => {
    assert.deepEqual(texts(parseDeep('A<if(X,<br>)>B')), ['<if(X,<br>)>', '<br>']);
  });
  it('distinct без повторов', () => {
    assert.deepEqual(distinctTags('A<br>B<colortype(0)>C<br>'), ['<br>', '<colortype(0)>']);
  });
});

describe('validate', () => {
  it('пропускает совпадающие и пустые', () => {
    assert.deepEqual(validateTags('A<br>', 'А<br>'), []);
    assert.deepEqual(validateTags('A<br>', ''), []);
    assert.deepEqual(validateTags('A<br>', null), []);
  });
  it('вложенные теги не считаются незакрытыми', () => {
    assert.deepEqual(validateTags('Yes<br>No<if([gnum1==2],<br>Yes)>', 'Да<br>Нет<if([gnum1==2],<br>Да)>'), []);
  });
  it('одна опечатка — одна ошибка с позицией и длиной', () => {
    const errs = validateTags('Grants <colortype(506)>X!', 'Дарует <colortype(506)>Бред<edgecolortype:507>!');
    assert.equal(errs.length, 1);
    assert.equal(errs[0].pos, 27);
    assert.ok(errs[0].len > 10);
    assert.match(errs[0].message, /позиция 27/);
  });
});

describe('warnings', () => {
  it('переведённые ветки if тихие', () => {
    assert.deepEqual(warnTags('A<if([gnum1==2],Yes,No)>B', 'А<if([gnum1==2],Да,Нет)>Б'), []);
  });
  it('находят отсутствующие и новые', () => {
    const w = warnTags('A<colortype(506)>B<edgecolortype(507)>', 'А<colortype(506)>Б<br>');
    assert.equal(w.length, 2);
    assert.match(w[0], /нет тега <edgecolortype\(507\)>/);
    assert.match(w[1], /новый тег <br>/);
  });
  it('изменённое условие if видно', () => {
    const w = warnTags('A<if([gnum1==2],Yes,No)>', 'А<if([gnum1==3],Да,Нет)>');
    assert.equal(w.length, 2);
    assert.match(w[0], /<if\(\[gnum1==2\]\)>/);
  });
  it('порядок и пустой перевод не шумят', () => {
    assert.deepEqual(warnTags('A<br>B<colortype(0)>', 'А<colortype(0)>Б<br>'), []);
    assert.deepEqual(warnTags('A<br>', ''), []);
  });
});

describe('kinds', () => {
  it('категории по Lumina MacroCode', () => {
    assert.equal(tagKindOf('<br>'), 'break');
    assert.equal(tagKindOf('<COLORTYPE(504)>'), 'color');
    assert.equal(tagKindOf('<ifpcgender(x,m,f)>'), 'logic');
    assert.equal(tagKindOf('<num(x)>'), 'value');
    assert.equal(tagKindOf('<icon(5)>'), 'media');
    assert.equal(tagKindOf('<italic(1)>'), 'fmt');
    assert.equal(tagKindOf('<whatever>'), 'misc');
  });
  it('имена тегов', () => {
    assert.equal(tagNameOf('<colortype(506)>'), 'colortype');
    assert.equal(tagNameOf('<payload: 02>'), 'payload');
  });
  it('нормализация логики', () => {
    assert.equal(normalizedTag('<if([gnum1==2],Yes,No)>'), '<if([gnum1==2])>');
    assert.equal(normalizedTag('<br>'), '<br>');
  });
  it('missing keys', () => {
    assert.deepEqual(findMissingTags('A<br>B', 'А<br>'), []);
    assert.deepEqual(findMissingTags('A<br>B', 'А'), ['<br>']);
  });
});

describe('render', () => {
  it('подсветка без вложенных mark', () => {
    const h = highlightTags('A<br>B<br>'.repeat(20));
    assert.equal(h.match(/<mark/g).length, 40);
    assert.ok(!h.includes('<mark class="ptoken"><mark'));
  });
  it('классы категорий и тултипы', () => {
    const h = highlightTags('A<br>B');
    assert.ok(h.includes('tk-break'));
    assert.ok(h.includes('Перенос строки'));
  });
  it('флаг пропущенных', () => {
    const h = highlightTags('A<br>B', ['<br>']);
    assert.ok(h.includes('tk-missing'));
  });
  it('превью красит и прячет цветные теги', () => {
    const p = renderGamePreview('A<colortype(506)>B<edgecolortype(0)>C<br>D');
    assert.ok(p.includes('color:#'));
    assert.ok(!p.includes('colortype'));
    assert.ok(p.includes('<br>'));
  });
  it('превью метит битые места', () => {
    const tr = 'X<edgecolortype:507>Y';
    const p = renderGamePreview(tr, validateTags('', tr));
    assert.ok(p.includes('tag-err'));
    assert.ok(p.includes('data-err="1"'));
  });
});

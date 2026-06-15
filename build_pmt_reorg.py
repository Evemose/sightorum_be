# -*- coding: utf-8 -*-
"""Reorganize the ПМТ part of диплом-фінал_30.06.docx to the 4-section template
(ОБ'ЄКТ / МЕТА / МЕТОДИ / ЗАСОБИ ТА ПОРЯДОК), matching приклад Шабанова.
Writes a new file; original untouched."""
import zipfile, shutil, copy, os
from lxml import etree

SRC = 'диплом-фінал_30.06.docx'
DST = 'диплом-фінал_30.06.reorg.docx'
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
XML = '{http://www.w3.org/XML/1998/namespace}'
W14 = 'http://schemas.microsoft.com/office/word/2010/wordml'

zin = zipfile.ZipFile(SRC)
doc_xml = zin.read('word/document.xml')
root = etree.fromstring(doc_xml)
body = root.find(W + 'body')
ch = list(body)

def ptext(p):
    return ''.join(t.text or '' for t in p.iter(W + 't'))

# ---- templates (capture clean copies BEFORE mutating) ----
HEAD_TPL = copy.deepcopy(ch[4359])   # Heading1
BODY_TPL = copy.deepcopy(ch[4360])   # normal paragraph (no pPr)

# dash glyph + separator taken verbatim from existing bullet 4362 ("− ...")
DASH = ptext(ch[4362])[0]            # U+2212 minus sign used as bullet marker

def mk_para(tpl, text):
    p = copy.deepcopy(tpl)
    for a in list(p.attrib):
        if a.startswith('{' + W14 + '}'):
            del p.attrib[a]
    for c in list(p):
        if etree.QName(c).localname != 'pPr':
            p.remove(c)
    r = etree.SubElement(p, W + 'r')
    rpr = etree.SubElement(r, W + 'rPr')
    etree.SubElement(rpr, W + 'lang').set(W + 'bidi', 'uk-UA')
    t = etree.SubElement(r, W + 't')
    t.set(XML + 'space', 'preserve')
    t.text = text
    return p

def body_para(text):
    return mk_para(BODY_TPL, text)

def bullet(text):
    return mk_para(BODY_TPL, DASH + ' ' + text)

def heading(text):
    return mk_para(HEAD_TPL, text)

def add_pagebreak_before(p):
    pPr = p.find(W + 'pPr')
    if pPr is None:
        pPr = etree.Element(W + 'pPr')
        p.insert(0, pPr)
    if pPr.find(W + 'pageBreakBefore') is not None:
        return
    pbb = etree.Element(W + 'pageBreakBefore')
    pstyle = pPr.find(W + 'pStyle')
    if pstyle is not None:
        pstyle.addnext(pbb)
    else:
        pPr.insert(0, pbb)

# ===== build the new ПМТ body sequence =====
new_seq = []

# §1 ОБ'ЄКТ ВИПРОБУВАНЬ — keep existing 4359..4365 verbatim
s1 = ch[4359]
add_pagebreak_before(s1)            # start section on a fresh page (as in Шабанов)
new_seq += [s1, ch[4360], ch[4361], ch[4362], ch[4363], ch[4364], ch[4365]]

# §2 МЕТА ТЕСТУВАННЯ — keep heading, template opening phrase + goal bullets
new_seq += [ch[4366]]
new_seq += [body_para('Метою тестування є наступне:')]
new_seq += [bullet('перевірка коректності виконання основних функціональних шляхів відповідно '
                    'до функціональних вимог — імпорту, перегляду наборів, аналітичного діалогу, '
                    'описового аналізу, причинно-наслідкового дослідження;')]
new_seq += [ch[4370], ch[4371], ch[4372]]

# §3 МЕТОДИ ТЕСТУВАННЯ (was §5) — moved up, dash bullets "термін – опис"
new_seq += [heading('3 МЕТОДИ ТЕСТУВАННЯ')]
new_seq += [body_para('Для тестування програмного забезпечення RORM використовуються такі методи:')]
new_seq += [bullet('модульне тестування – перевірка коректності окремих методів і класів в '
                   'ізоляції з підставними залежностями; засоби — JUnit 5, AssertJ, Mockito;')]
new_seq += [bullet('інтеграційне тестування – перевірка взаємодії компонентів з реальними '
                   'зовнішніми сервісами (PostgreSQL, Valkey, Restate), що підіймаються через '
                   'Testcontainers;')]
new_seq += [bullet('функціональне тестування «чорної скриньки» – перевірка відповідності '
                   'зовнішньо спостережуваної поведінки сформульованим функціональним вимогам;')]
new_seq += [bullet('навантажувальне тестування – перевірка дотримання часових нефункціональних '
                   'вимог за різних обсягів даних і кількості одночасних клієнтів.')]

# §4 ЗАСОБИ ТА ПОРЯДОК ТЕСТУВАННЯ — засоби + порядок, both per template phrasing
new_seq += [heading('4 ЗАСОБИ ТА ПОРЯДОК ТЕСТУВАННЯ')]
new_seq += [body_para('Під час проведення тестування будуть використовуватись наступні '
                      'допоміжні засоби:')]
for tline in [
    'JUnit 5, AssertJ, Mockito, Spring Test — модульне та інтеграційне тестування Java-коду;',
    'Testcontainers — запуск реальних екземплярів PostgreSQL і Valkey у Docker-контейнерах на час тесту;',
    'JaCoCo — вимірювання покриття Java-коду;',
    'pytest з плагіном pytest-cov — тестування Python-сервісу обчислювального шару;',
    'Jest, ts-jest та aws-cdk-lib/assertions — тестування інфраструктури як коду (AWS CDK);',
    'спеціалізований інструмент на httpx, asyncio та numpy — навантажувальне тестування часових вимог;',
    'вебоглядач на основі рушія Chromium і Playwright (MCP) — ручне функціональне тестування.',
]:
    new_seq += [bullet(tline)]
new_seq += [body_para('Порядок проведення тестування буде наступним:')]
for oline in [
    'модульні та інтеграційні Java-тести (./gradlew clean test jacocoTestReport);',
    'модульні тести Python-сервісу обчислювального шару (pytest);',
    'тестування інфраструктури як коду на TypeScript (npm test);',
    'ручне функціональне тестування за функціональними вимогами FR-1 — FR-5;',
    'навантажувальне тестування часових нефункціональних вимог.',
]:
    new_seq += [bullet(oline)]

# ===== splice: remove old ПМТ body 4359..4640, insert new_seq before КК title (4641) =====
kk_title = ch[4641]
assert 'Факультет' in ptext(kk_title), ptext(kk_title)[:50]
add_pagebreak_before(kk_title)       # КК title page starts fresh (old spacer empties removed)

# detach kept refs first so re-insertion is clean, then drop the whole old range
old_range = ch[4359:4641]            # 4359..4640 inclusive
for el in old_range:
    body.remove(el)
for el in new_seq:                   # new_seq holds kept refs + freshly built paras
    kk_title.addprevious(el)

# ===== rebuild ЗМІСТ table (4356): 9 rows -> 4 =====
# Rows 3,4 are rebuilt by cloning the black row 1 so they inherit its colour,
# font and dotted-leader glyph (the original rows 3,4 were red and a wider font).
tbl = ch[4356]
rows = tbl.findall(W + 'tr')

def first_par_runs(cell):
    p = cell.find(W + 'p')
    return p, p.findall(W + 'r')

def set_single(cell, text):
    p, runs = first_par_runs(cell)
    if not runs:
        r = etree.SubElement(p, W + 'r')
        etree.SubElement(r, W + 't')
        runs = [r]
    for extra in runs[1:]:
        p.remove(extra)
    t = runs[0].find(W + 't')
    if t is None:
        t = etree.SubElement(runs[0], W + 't')
    t.set(XML + 'space', 'preserve')
    t.text = text

DOT = '…'

def build_zmist_row(tpl_row, num, title, n_dots, page):
    row = copy.deepcopy(tpl_row)
    for el in row.iter():
        for a in list(el.attrib):
            if a.startswith('{' + W14 + '}'):
                del el.attrib[a]
    cells = row.findall(W + 'tc')
    set_single(cells[0], str(num))
    p, runs = first_par_runs(cells[1])
    for extra in runs[2:]:
        p.remove(extra)
    runs[0].find(W + 't').text = title
    dt = runs[1].find(W + 't')
    dt.set(XML + 'space', 'preserve')
    dt.text = DOT * n_dots
    set_single(cells[2], str(page))
    return row

tpl_row = rows[0]
# dot counts fixed by Times New Roman metrics so the leader matches row 1's
# right edge without overflowing the cell (which would wrap to a second line)
new3 = build_zmist_row(tpl_row, 3, 'МЕТОДИ ТЕСТУВАННЯ', 17, 5)
new4 = build_zmist_row(tpl_row, 4, 'ЗАСОБИ ТА ПОРЯДОК ТЕСТУВАННЯ', 11, 6)
rows[1].addnext(new4)        # after row 2
rows[1].addnext(new3)        # ends up row2, new3, new4
for r in rows[2:]:           # drop original rows 3..9
    tbl.remove(r)

# ===== write new docx =====
new_doc = etree.tostring(root, xml_declaration=True, encoding='UTF-8', standalone=True)
if os.path.exists(DST):
    os.remove(DST)
with zipfile.ZipFile(SRC) as zsrc, zipfile.ZipFile(DST, 'w', zipfile.ZIP_DEFLATED) as zdst:
    for item in zsrc.infolist():
        data = zsrc.read(item.filename)
        if item.filename == 'word/document.xml':
            data = new_doc
        zdst.writestr(item, data)
print('WROTE OK')

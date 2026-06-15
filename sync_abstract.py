# -*- coding: utf-8 -*-
"""Synchronise Ukrainian АНОТАЦІЯ (blocks 70-77) and English ABSTRACT (blocks 80-86)
by content + identical keyword sets. Edits operate by string transforms on the
existing paragraph text so apostrophes/dashes already in the file are preserved."""
import zipfile, copy, os
from lxml import etree

SRC = 'диплом-фінал_30.06.reorg.docx'
DST = 'диплом-фінал_30.06.reorg.docx'   # in place; pristine original kept in BACKUP
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
XML = '{http://www.w3.org/XML/1998/namespace}'
W14 = 'http://schemas.microsoft.com/office/word/2010/wordml'

zin = zipfile.ZipFile(SRC)
root = etree.fromstring(zin.read('word/document.xml'))
body = root.find(W + 'body')
ch = list(body)

def ptext(p):
    return ''.join(t.text or '' for t in p.iter(W + 't'))

def set_text(p, text):
    """Collapse paragraph to a single run carrying `text`, reusing the first
    run's rPr (keeps font + proofing language) and the paragraph's pPr."""
    pPr = p.find(W + 'pPr')
    first_r = p.find(W + 'r')
    rpr = None
    if first_r is not None and first_r.find(W + 'rPr') is not None:
        rpr = copy.deepcopy(first_r.find(W + 'rPr'))
    for c in list(p):
        if c is not pPr:
            p.remove(c)
    r = etree.SubElement(p, W + 'r')
    if rpr is not None:
        r.append(rpr)
    else:
        etree.SubElement(etree.SubElement(r, W + 'rPr'), W + 'lang').set(W + 'bidi', 'uk-UA')
    t = etree.SubElement(r, W + 't')
    t.set(XML + 'space', 'preserve')
    t.text = text

def edit(idx, *replacements):
    p = ch[idx]
    txt = ptext(p)
    for old, new in replacements:
        assert old in txt, f'block {idx}: not found: {old!r}'
        txt = txt.replace(old, new)
    set_text(p, txt)
    return txt

# ---------- Ukrainian АНОТАЦІЯ ----------
# 72 purpose: add the "performed by a data analyst over weeks" clause (present in EN)
edit(72, ('інтерпретації результату.',
          'інтерпретації результату, які у поточній практиці виконує аналітик '
          'даних протягом тижнів.'))
# 73 preproject/BPMN: fix typo змодельвано -> змодельовано
edit(73, ('змодельвано', 'змодельовано'))
# 74 swarm: name the five phases (present in EN)
edit(74, ('фазами зі змагальною',
          'фазами (розвідка, генерування гіпотез, компіляція, термінальне '
          'оцінювання, вердикт судді) зі змагальною'))
# 77 keywords: 7 -> 10, same set/order as EN
edit(77,
     ('КЛЮЧОВІ СЛОВА: ', 'КЛЮЧОВІ СЛОВА: СТАТИСТИЧНИЙ АНАЛІЗ, ПРИЧИННО-НАСЛІДКОВИЙ ВИВІД, '),
     ('МЕТАМОДЕЛЬ, AWS', 'МЕТАМОДЕЛЬ, ОПИСОВИЙ АНАЛІЗ, AWS'))

# ---------- English ABSTRACT ----------
# 80: drop "approximately" (UA says exactly 120)
edit(80, ('in total approximately 120 pages', 'in total 120 pages'))
# 82 purpose: drop "or data scientist" so it mirrors UA "аналітик даних"
edit(82, ('performed by a data analyst or data scientist over weeks',
          'performed by a data analyst over weeks'))
# 83 swarm: drop the long structural-check parenthetical (not in UA) to keep parity + page fit
edit(83, ('the descriptive level (distribution multimodality, seasonality, structural '
          'breakpoints, sign inversion under stratification) and quantitative',
          'the descriptive level and quantitative'))
# insert EN counterpart of UA-73 (preproject/BPMN) after purpose (82), before swarm (83)
bpmn = copy.deepcopy(ch[81])
for el in bpmn.iter():
    for a in list(el.attrib):
        if a.startswith('{' + W14 + '}'):
            del el.attrib[a]
set_text(bpmn, 'In the preproject investigation of the subject area, an analysis of '
               'existing solutions is performed and the business process is modelled '
               'in BPMN notation.')
ch[82].addnext(bpmn)
# 86 keywords already the canonical 10 set -> unchanged

# strip the draft red highlighting across the whole АНОТАЦІЯ + ABSTRACT
# (UA BPMN paragraph and EN KEYWORDS were red) so the final reference is uniform black
def strip_red(p):
    for color in p.findall('.//' + W + 'color'):
        if (color.get(W + 'val') or '').upper() == 'FF0000':
            color.getparent().remove(color)

for i in range(70, 87):
    strip_red(ch[i])
strip_red(bpmn)

new_doc = etree.tostring(root, xml_declaration=True, encoding='UTF-8', standalone=True)
tmp = DST + '.tmp'
with zipfile.ZipFile(SRC) as zsrc, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zdst:
    for item in zsrc.infolist():
        data = zsrc.read(item.filename)
        if item.filename == 'word/document.xml':
            data = new_doc
        zdst.writestr(item, data)
zin.close()
os.replace(tmp, DST)
print('SYNC OK')

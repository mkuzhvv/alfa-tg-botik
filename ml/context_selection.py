"""Непрерывное окно исходного текста. Никакой генерации и склейки цитат."""
from functools import lru_cache
import math
import re


@lru_cache(maxsize=1)
def tokenizer():
    from lexical import LexicalIndex
    return LexicalIndex([])


def select_window(text, query, target=1100):
    if len(text) <= target:
        return text, (0, len(text))
    # Sentence/paragraph boundaries preserve exact offsets in the original source.
    matches = list(re.finditer(r'[^\n.!?]+(?:[.!?](?=\s|$)|\n|$)', text))
    units = [(m.start(), m.end(), m.group().strip()) for m in matches if m.group().strip()]
    if not units:
        return text[:target], (0, target)
    terms = set(tokenizer().tokens(query))
    definition_query = bool(re.search(r'что|кого|чем|зачем|для чего|почему|означает|называют', query, re.I))
    def score(unit):
        words = set(tokenizer().tokens(unit[2]))
        overlap = len(terms & words)
        definition = bool(re.search(r'— это|называют|означает|позволяет|помогает', unit[2], re.I))
        heading = len(unit[2]) < 80 and not re.search(r'[.!?—]', unit[2])
        introduction = bool(re.search(r'в этой статье|разберем|разберём|вы узнаете|рассмотрим', unit[2], re.I))
        return overlap/(1+math.log1p(len(words))/4) + (.9 if definition_query else .15)*definition - .4*heading - .5*introduction
    anchor = max(range(len(units)), key=lambda i: (score(units[i]), -i))
    left = right = anchor
    while True:
        candidates = []
        if left > 0 and units[right][1]-units[left-1][0] <= target:
            candidates.append((score(units[left-1]), "left"))
        if right+1 < len(units) and units[right+1][1]-units[left][0] <= target:
            candidates.append((score(units[right+1]), "right"))
        if not candidates:
            break
        if max(candidates)[1] == "left":
            left -= 1
        else:
            right += 1
    start, end = units[left][0], units[right][1]
    # A pathological single sentence can exceed the target; preserve its provenance.
    end = min(end, start+target)
    selected = text[start:end]
    return selected, (start, end)

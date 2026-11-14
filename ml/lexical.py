"""BM25 с русским стеммингом; RRF не выдаётся за вероятность релевантности."""
from collections import Counter, defaultdict
from functools import lru_cache
import math
import re
import unicodedata


STOP = set("и в во на с со к ко у о об обо по из за от до для при а но или что это как какой какая какие чем чего зачем почему где когда кто мне меня мы вы ваш мой свой его ее их уже ещё еще бы быть есть только такой так не ли же то все всего нужно надо можно сделать делать подскажите расскажи объясни пожалуйста бизнес бизнеса компании предпринимателю небольшой малого".split())


def normalize_text(text):
    return unicodedata.normalize("NFKC", text).casefold().replace("ё", "е").translate(
        {ord(c): None for c in "\u200b\u200c\u200d\ufeff"})


class LexicalIndex:
    def __init__(self, documents):
        import snowballstemmer
        self.stemmer = snowballstemmer.stemmer("russian")
        self.ids = []
        self.lengths = {}
        self.postings = defaultdict(dict)
        for item_id, title, text in documents:
            tokens = self.tokens(title) * 3 + self.tokens(text)
            self.ids.append(item_id)
            self.lengths[item_id] = len(tokens)
            for term, count in Counter(tokens).items():
                self.postings[term][item_id] = count
        self.average_length = sum(self.lengths.values()) / max(1, len(self.ids))

    @lru_cache(maxsize=100000)
    def stem(self, word):
        return self.stemmer.stemWord(word)

    def tokens(self, text):
        replacements = {"црм": "crm", "свот": "swot", "авс": "abc", "сapex": "capex"}
        words = re.findall(r"[a-zа-я0-9]+", normalize_text(text))
        return [self.stem(replacements.get(w, w)) for w in words if w not in STOP and len(w) > 1]

    def rank(self, query):
        scores = defaultdict(float)
        for term in set(self.tokens(query)):
            posting = self.postings.get(term, {})
            idf = math.log(1 + (len(self.ids)-len(posting)+.5)/(len(posting)+.5))
            for item_id, frequency in posting.items():
                length = self.lengths[item_id] / max(1, self.average_length)
                scores[item_id] += idf * frequency * 2.2 / (frequency + 1.2 * (.25 + .75*length))
        return sorted(scores.items(), key=lambda row: (-row[1], row[0]))


def reciprocal_rank_fusion(dense, lexical, constant=60, dense_weight=.5, window=100):
    scores = defaultdict(float)
    for ranking, weight in [(dense, dense_weight), (lexical, 1-dense_weight)]:
        for position, item_id in enumerate(ranking[:window], 1):
            scores[item_id] += weight/(constant+position)
    return sorted(scores, key=lambda item_id: (-scores[item_id], item_id)), dict(scores)

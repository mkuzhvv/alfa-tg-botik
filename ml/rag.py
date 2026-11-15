#!/usr/bin/env python3
"""Локальная подготовка корпуса Альфа-Курса. Чтение БД.

Этапы: export → audit → prepare → validate → index → retrieve → ask.
search остаётся лексическим baseline; локальный runtime находится в rag_runtime.py.
"""

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
from html import unescape
from html.parser import HTMLParser
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import unquote, urlsplit, urlunsplit
from uuid import UUID, uuid5


ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "0.1.0"
MATERIAL_NS = UUID("a1fa0000-0000-4000-8000-000000000001")
CHUNK_NS = UUID("a1fa0000-0000-4000-8000-000000000002")
TARGET_CHARS = 2400
MAX_CHARS = 6000
KINDS = {
    "Articles": "ARTICLE", "Courses": "COURSE", "Lessons": "LESSON",
    "Documents": "DOCUMENT", "Glossary": "GLOSSARY", "Webinars": "WEBINAR",
    "Podcasts": "PODCAST", "Calculators": "CALCULATOR",
}
BLOCKS = {
    "Articles": ("ArticleEmbedItems", "ArticleId"),
    "Lessons": ("LessonEmbedItems", "LessonId"),
    "Documents": ("DocumentEmbedItems", "DocumentId"),
    "Glossary": ("GlossaryEmbedItems", "GlossaryId"),
}
TABLES = {
    "Statuses": "Id Title",
    "CourseTypes": "Id Title IsDeleted",
    "Articles": "Id StatusId Title Description Duration IsDeleted Published IsPublic IsClosed Url Modified SubjectId",
    "Courses": "Id StatusId Title Description Duration IsDeleted Published IsClosed Url Modified Cost TypeId",
    "Lessons": "Id Title Description Duration IsDeleted IsGroup Modified",
    "Documents": "Id StatusId Title Description IsDeleted Published Url Modified",
    "Glossary": "Id StatusId Title Description Duration IsDeleted Published Url Modified",
    "Webinars": "Id StatusId Title Description Duration IsDeleted IsClosed Url Modified IsPasswordRequired IsRegistrationRequired",
    "Podcasts": "Id StatusId Title Description Duration IsDeleted IsClosed Url Modified Cost",
    "Calculators": "Id Title Description IsDeleted Url Modified",
    "ContentSettings": "Id EntityId StatusId IsDeleted Published",
    "Topics": "Id Title IsDeleted IsPublic",
    "Industries": "Id Title IsDeleted",
    "ContentSettingsTopics": "ContentSettingsId TopicId Order",
    "ContentSettingsIndustries": "ContentSettingsId IndustryId Order",
    "CourseLessonBindings": "Id CourseId LessonId ParentGroupId Order IsDeleted IsClosed ClosedUntil IsTrial",
    "CourseSubscriptions": "SubscriptionId CourseId",
    "CourseTariffBindings": "Id CourseId Cost",
}
for _table, _fk in BLOCKS.values():
    TABLES[_table] = f"Id {_fk} Type HtmlContent Params Order"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def dump_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


class TextParser(HTMLParser):
    """Сохраняет границы абзацев, списков и ячеек, пропускает исполняемый HTML."""

    SKIP = {"script", "style", "iframe", "noscript", "svg", "form", "button", "nav"}
    BREAK = {"p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "table", "tr", "blockquote"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.skipped = []
        self.links = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if self.skipped:
            if tag in self.SKIP:
                self.skipped.append(tag)
            return
        if tag in self.SKIP:
            self.skipped.append(tag)
            return
        if tag == "a" and attrs.get("href"):
            self.links.append(attrs["href"])
        if tag in self.BREAK:
            self.parts.append("\n\n")
        elif tag == "li":
            self.parts.append("\n- ")
        elif tag == "br":
            self.parts.append("\n")
        elif tag in {"td", "th"}:
            self.parts.append(" | ")

    def handle_endtag(self, tag):
        if self.skipped:
            if tag == self.skipped[-1]:
                self.skipped.pop()
            return
        if tag in self.BREAK or tag == "li":
            self.parts.append("\n\n" if tag in self.BREAK else "\n")

    def handle_data(self, data):
        if not self.skipped:
            self.parts.append(data)


def clean_html(value):
    if not isinstance(value, str) or not value.strip():
        return ""
    parser = TextParser()
    parser.feed(value)
    text = "".join(parser.parts).replace("\xa0", " ")
    text = re.sub(r"[\u200b\u2060\ufeff\u00ad]", "", text)
    text = re.sub(r"data:[^\s\"<>]+", "", text, flags=re.I)
    lines = [re.sub(r"[\t \r\f\v]+", " ", line).strip(" |") for line in text.splitlines()]
    text = "\n".join(lines)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def params_object(value):
    if isinstance(value, dict):
        return value
    try:
        obj = json.loads(value or "{}")
        return obj if isinstance(obj, dict) else {}
    except (ValueError, TypeError):
        return {}


def block_text(block):
    kind = block.get("Type")
    params = params_object(block.get("Params"))
    # Изображения, видео, файлы, галереи, реклама и опросы не являются текстом знания.
    if kind not in {0, 2, 3, 4, 13}:
        return ""
    # Type 0 + link — карточка перехода, а не содержание целевой статьи.
    if kind == 0 and params.get("link"):
        return clean_html(block.get("HtmlContent"))
    parts = [clean_html(block.get("HtmlContent"))]
    for key in ("title", "description", "text"):
        if isinstance(params.get(key), str):
            parts.append(clean_html(params[key]))
    if kind == 4 and isinstance(params.get("items"), list):
        for item in params["items"]:
            if isinstance(item, str):
                parts.append("- " + clean_html(item))
            elif isinstance(item, dict):
                for key in ("title", "text", "description"):
                    if isinstance(item.get(key), str):
                        parts.append(clean_html(item[key]))
    return "\n\n".join(dict.fromkeys(p for p in parts if p))


def split_text(text, target=TARGET_CHARS):
    """Режет по абзацам/предложениям; лимит символов не выдаётся за точные токены."""
    if not 100 <= target <= MAX_CHARS:
        raise ValueError("target должен быть от 100 до 6000 символов")
    units = []
    for paragraph in re.split(r"\n\s*\n", text):
        paragraph = paragraph.strip()
        if not paragraph:
            continue
        if len(paragraph) <= target:
            units.append(paragraph)
            continue
        sentences = re.split(r"(?<=[.!?])\s+(?=[А-ЯA-ZЁ])", paragraph)
        for sentence in sentences:
            while len(sentence) > target:
                end = sentence.rfind(" ", 0, target + 1)
                if end < target // 2:
                    end = target
                units.append(sentence[:end].strip())
                sentence = sentence[end:].strip()
            if sentence:
                units.append(sentence)
    chunks, current = [], ""
    for unit in units:
        combined = (current + "\n\n" + unit).strip()
        if current and len(combined) > target:
            chunks.append(current)
            current = unit
        else:
            current = combined
    if current:
        if len(current) < 30 and chunks and len(chunks[-1]) + len(current) + 2 <= MAX_CHARS:
            chunks[-1] += "\n\n" + current
        else:
            chunks.append(current)
    return [c for c in chunks if len(c) >= 30]


def canonical_url(value):
    """Только наблюдавшиеся HTTPS URL платформы, без query и fragment."""
    if not isinstance(value, str):
        return None
    try:
        url = urlsplit(unescape(value).strip())
        if url.scheme != "https" or url.netloc.lower() not in {"kurs.alfabank.ru", "rb.ru"}:
            return None
        if url.netloc.lower() == "rb.ru" and not url.path.startswith("/kurs/"):
            return None
        return urlunsplit(("https", url.netloc.lower(), url.path, "", ""))
    except ValueError:
        return None


def walk_strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for v in value.values():
            yield from walk_strings(v)
    elif isinstance(value, list):
        for v in value:
            yield from walk_strings(v)


def link_registry(data):
    """Индекс настоящих ссылок из HTML/Params; префиксы страниц не придумываются."""
    urls = set()
    for table in [*KINDS, *(t for t, _ in BLOCKS.values())]:
        for row in data.get(table, []):
            for value in walk_strings(row):
                candidates = re.findall(r'https://[^\s<>"\'\\]+', unescape(value))
                for candidate in candidates:
                    url = canonical_url(candidate)
                    if url:
                        urls.add(url)
    registry = defaultdict(set)
    for url in urls:
        path = unquote(urlsplit(url).path)
        if path.startswith("/kurs/"):
            path = path[len("/kurs"):]
        registry[path.strip("/")].add(url)
    return registry


PREFIXES = {
    "ARTICLE": "/articles/", "COURSE": "/courses/", "DOCUMENT": "/documents/",
    "GLOSSARY": "/vocabulary/", "WEBINAR": "/webinars/", "PODCAST": "/podcasts/",
    "CALCULATOR": "/calculators/",
}


def resolve_url(row, kind, registry):
    raw = (row.get("Url") or "").strip()
    direct = canonical_url(raw)
    if direct:
        return direct
    slug = unquote(raw).strip("/")
    if not slug:
        return None
    prefix = PREFIXES.get(kind, "").strip("/")
    expected = slug if slug.startswith(prefix + "/") else prefix + "/" + slug
    # Точное совпадение пути и вида сущности; URL урока не подменяет URL курса.
    candidates = registry.get(expected, set())
    return min(candidates, key=lambda u: (urlsplit(u).netloc != "kurs.alfabank.ru", len(u), u)) if candidates else None


def utc(value):
    if not value:
        return None
    value = value.replace("Z", "+00:00")
    # PostgreSQL выводит 1–6 знаков дробной секунды; Python 3.9 принимает 3/6.
    value = re.sub(r"\.(\d+)(?=[+-]\d\d:\d\d$)",
                   lambda m: "." + m[1][:6].ljust(6, "0"), value)
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        raise ValueError("Дата должна содержать UTC Z или смещение часового пояса")
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def after(value, reference):
    return datetime.fromisoformat(utc(value).replace("Z", "+00:00")) > datetime.fromisoformat(utc(reference).replace("Z", "+00:00"))


def available(row, published_ids, now):
    if row.get("IsDeleted") is not False:
        return "deleted_or_unknown"
    if row.get("IsClosed") is True:
        return "closed"
    if "StatusId" not in row or row["StatusId"] not in published_ids:
        return "not_published_or_unknown"
    if row.get("Published") and after(row["Published"], now):
        return "future_publication"
    return None


def material_id(kind, source_id):
    return str(uuid5(MATERIAL_NS, kind + ":" + source_id))


def build(data, now):
    published = {s["Id"] for s in data["Statuses"] if s["Title"].strip().casefold() == "опубликовано"}
    if len(published) != 1:
        raise ValueError("Не удалось однозначно определить статус 'Опубликовано'")
    registry = link_registry(data)
    course_types = {r["Id"]: r["Title"].strip().casefold() for r in data["CourseTypes"] if r.get("IsDeleted") is False}
    subscriptions = {r["CourseId"] for r in data["CourseSubscriptions"]}
    tariffs = defaultdict(list)
    for row in data["CourseTariffBindings"]:
        tariffs[row["CourseId"]].append(row.get("Cost"))
    settings = defaultdict(list)
    for row in data["ContentSettings"]:
        settings[row["EntityId"]].append(row)
    tags = {}
    for field, table, bindings, key in [
        ("topics", "Topics", "ContentSettingsTopics", "TopicId"),
        ("industries", "Industries", "ContentSettingsIndustries", "IndustryId"),
    ]:
        names = {r["Id"]: clean_html(r["Title"]).casefold() for r in data[table]
                 if r.get("IsDeleted") is False and (field != "topics" or r.get("IsPublic") is True)}
        mapping = defaultdict(set)
        for row in data[bindings]:
            if row[key] in names:
                mapping[row["ContentSettingsId"]].add(names[row[key]])
        tags[field] = mapping
    block_index = {}
    for table, (block_table, fk) in BLOCKS.items():
        rows = defaultdict(list)
        for row in data[block_table]:
            rows[row[fk]].append(row)
        for values in rows.values():
            values.sort(key=lambda r: (r["Order"], r["Id"]))
        block_index[table] = rows

    exclusions, warnings, evidence = [], Counter(), []
    materials, chunks = [], []
    source_courses = {r["Id"]: r for r in data["Courses"]}
    kept_courses = {}
    course_policy = {}
    for row in data["Courses"]:
        reason = available(row, published, now)
        cost = row.get("Cost")
        kind = course_types.get(row.get("TypeId"))
        if cost is not None and (type(cost) is not int or cost < 0):
            reason = reason or "invalid_cost"
        if kind not in {"открытый", "платный"} or row["Id"] in subscriptions:
            reason = reason or "subscription_or_access_unconfirmed"
        if kind == "платный" and (cost is None or cost <= 0):
            reason = reason or "conflicting_access_flags"
        if kind == "открытый" and cost not in {None, 0}:
            reason = reason or "conflicting_access_flags"
        if any(t is None or t < 0 or (t > 0 and cost in {None, 0}) for t in tariffs[row["Id"]]):
            reason = reason or "tariff_access_unconfirmed"
        course_policy[row["Id"]] = reason

    def exclude(table, row, reason):
        exclusions.append({"sourceType": KINDS[table], "sourceId": row["Id"], "reason": reason})

    def emit(table, row, *, parents=None, inherited_url=None, forced_reason=None):
        kind = KINDS[table]
        reason = forced_reason
        if table != "Lessons":
            reason = reason or available(row, published, now)
        if table == "Articles" and row.get("IsPublic") is not True:
            reason = reason or "not_public"
        if table == "Webinars" and (row.get("IsPasswordRequired") is not False or row.get("IsRegistrationRequired") is not False):
            reason = reason or "registration_or_password_required"
        if table == "Courses":
            reason = reason or course_policy[row["Id"]]
        # Любая дополнительная настройка с конфликтом публикации закрывает материал.
        for setting in settings[row["Id"]]:
            reason = reason or available(setting, published, now)
        title = clean_html(row.get("Title"))
        if not title or len(title) > 250:
            reason = reason or "invalid_title"
        cost = row.get("Cost")
        if cost is not None and (type(cost) is not int or cost < 0):
            reason = reason or "invalid_cost"
        url = inherited_url or resolve_url(row, kind, registry)
        if not url:
            reason = reason or "unconfirmed_url"
        if reason:
            exclude(table, row, reason)
            return None
        paid = cost is not None and cost > 0
        mid = material_id(kind, row["Id"])
        descriptions = clean_html(row.get("Description"))
        record = {
            "schemaVersion": SCHEMA, "materialId": mid, "sourceType": kind,
            "sourceId": row["Id"], "title": title,
            "description": descriptions[:1000] or None, "url": url,
            "isPaid": paid, "answerAllowed": False,
            "externalMetadataAllowed": False,
            "duration": clean_html(row.get("Duration"))[:100] or None,
            "topics": [], "industries": [], "parentMaterialIds": parents or [],
            "sourceUpdatedAt": utc(row.get("Modified")),
        }
        if table == "Courses" or "Cost" in row:
            record["sourceCost"] = cost
        for field in ("topics", "industries"):
            record[field] = sorted({name for s in settings[row["Id"]] for name in tags[field][s["Id"]]})
        if table in block_index and not paid:
            texts, seen = [], set()
            for block in block_index[table].get(row["Id"], []):
                part = block_text(block)
                if part and part not in seen:
                    texts.append(part)
                    seen.add(part)
                elif part:
                    warnings["duplicate_blocks_removed"] += 1
            pieces = split_text("\n\n".join(texts))
            record["answerAllowed"] = bool(pieces)
            if not pieces:
                warnings[f"empty_text_{kind}"] += 1
            for order, piece in enumerate(pieces, 1):
                chunks.append({
                    "schemaVersion": SCHEMA,
                    "chunkId": str(uuid5(CHUNK_NS, mid + ":" + str(order))),
                    "materialId": mid, "order": order, "heading": None,
                    "text": piece, "contentHash": sha256(piece.encode("utf-8")),
                    "externalTextAllowed": False,
                })
        materials.append(record)
        evidence.append({"materialId": mid, "url": url,
                         "urlEvidence": "parent_course" if inherited_url else "source_field" if canonical_url(row.get("Url")) else "observed_in_dump",
                         "accessEvidence": "free_published_parent_courses" if table == "Lessons" else "publication_and_entity_access_flags",
                         "externalPermission": "unknown; denied"})
        return record

    for row in sorted(data["Courses"], key=lambda r: r["Id"]):
        record = emit("Courses", row)
        if record:
            kept_courses[row["Id"]] = record
    lesson_bindings = defaultdict(list)
    for binding in data["CourseLessonBindings"]:
        # Удалённая связь не предоставляет доступ и не закрывает другие активные связи.
        if binding.get("IsDeleted") is False:
            lesson_bindings[binding["LessonId"]].append(binding)
    lessons = {r["Id"]: r for r in data["Lessons"]}

    def binding_open(binding, visited=None):
        visited = set() if visited is None else set(visited)
        if binding["LessonId"] in visited:
            return False
        visited.add(binding["LessonId"])
        if (binding.get("IsClosed") is not False or
                (binding.get("ClosedUntil") and after(binding["ClosedUntil"], now))):
            return False
        group_id = binding.get("ParentGroupId")
        if not group_id:
            return True
        group = lessons.get(group_id)
        if not group or group.get("IsDeleted") is not False or group.get("IsGroup") is not True:
            return False
        group_bindings = [b for b in lesson_bindings[group_id] if b["CourseId"] == binding["CourseId"]]
        return len(group_bindings) == 1 and binding_open(group_bindings[0], visited)

    for row in sorted(data["Lessons"], key=lambda r: r["Id"]):
        reason = None
        bindings = lesson_bindings[row["Id"]]
        if row.get("IsDeleted") is not False or row.get("IsGroup") is not False:
            reason = "deleted_or_group"
        if not bindings:
            reason = reason or "orphan_lesson"
        for binding in bindings:
            parent = source_courses.get(binding["CourseId"])
            if (not parent or course_policy.get(binding["CourseId"]) or
                    parent.get("Cost") not in {None, 0} or
                    not binding_open(binding)):
                reason = reason or "restricted_or_ambiguous_parent"
            if binding["CourseId"] not in kept_courses:
                reason = reason or "parent_not_in_catalog"
        parents = sorted({kept_courses[b["CourseId"]]["materialId"] for b in bindings if b["CourseId"] in kept_courses})
        urls = sorted({kept_courses[b["CourseId"]]["url"] for b in bindings if b["CourseId"] in kept_courses})
        emit("Lessons", row, parents=parents, inherited_url=urls[0] if urls else None, forced_reason=reason)
    for table in ["Articles", "Documents", "Glossary", "Webinars", "Podcasts", "Calculators"]:
        for row in sorted(data[table], key=lambda r: r["Id"]):
            candidate = row
            if table == "Calculators":
                conf = settings[row["Id"]]
                candidate = dict(row, StatusId=conf[0].get("StatusId") if len(conf) == 1 else None)
            emit(table, candidate)
    materials.sort(key=lambda r: r["materialId"])
    chunks.sort(key=lambda r: (r["materialId"], r["order"]))
    report = {
        "schemaVersion": SCHEMA, "asOf": now,
        "sourceCounts": {table: len(data[table]) for table in KINDS},
        "materialsByType": dict(Counter(r["sourceType"] for r in materials)),
        "chunksCount": len(chunks), "paidMaterials": sum(r["isPaid"] for r in materials),
        "excludedByReason": dict(Counter(r["reason"] for r in exclusions)),
        "exclusions": exclusions, "warnings": dict(warnings), "evidence": evidence,
        "chunking": {"targetCharacters": TARGET_CHARS, "maxCharacters": MAX_CHARS,
                     "overlap": 0, "tokenizer": "not selected; character budget, not exact tokens"},
        "externalApi": "No calls. Unknown external permissions are false for every record.",
        "retrievalEvaluation": "Not performed: semantic model and human-labelled questions are not selected.",
        "limitations": ["Only observed absolute platform URLs; missing links exclude materials.",
                         "Subscription courses and lessons with any ambiguous parent are excluded.",
                         "Images, video/audio, files, ads and surveys are not transcribed.",
                         "No fallback from Content field: block tables are the source of answer text.",
                         "No current-law verification: content dates must be considered in answers."],
    }
    return materials, chunks, report


def export_snapshot(path):
    if path.exists():
        raise ValueError(f"Файл уже существует: {path}")
    pairs = []
    for table, fields in TABLES.items():
        columns = ", ".join('"' + f + '"' for f in fields.split())
        order = ", ".join('"' + f + '"' for f in fields.split() if f.endswith("Id") or f in {"Id", "Order"})
        pairs.append(f"'{table}', (SELECT COALESCE(json_agg(t), '[]'::json) FROM (SELECT {columns} FROM \"{table}\" ORDER BY {order}) t)")
    sql = "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSELECT json_build_object(" + ",\n".join(pairs) + ");\nCOMMIT;"
    result = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "alfa_source"],
                            input=sql, text=True, capture_output=True, cwd=ROOT, check=True)
    data = json.loads(result.stdout)
    path.parent.mkdir(parents=True, exist_ok=True)
    # JSON — локальная копия исходных материалов, хранить только в игнорируемой data/source.
    with path.open("x", encoding="utf-8") as out:
        json.dump(data, out, ensure_ascii=False)
        out.write("\n")
    print(json.dumps({"snapshot": str(path), "counts": {k: len(v) for k, v in data.items()}}, ensure_ascii=False, indent=2))


def write_jsonl(path, rows):
    with path.open("w", encoding="utf-8", newline="\n") as out:
        for row in rows:
            out.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def read_jsonl(path):
    rows = []
    with path.open(encoding="utf-8") as source:
        for line, text in enumerate(source, 1):
            try:
                if not text.strip():
                    raise ValueError("пустая строка")
                rows.append(json.loads(text))
            except ValueError as error:
                raise ValueError(f"{path.name}:{line}: {error}") from error
    return rows


def validate(folder):
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
    materials = read_jsonl(folder / "materials.jsonl")
    chunks = read_jsonl(folder / "chunks.jsonl")
    def require(ok, message):
        if not ok:
            raise ValueError(message)
    require(manifest["schemaVersion"] == SCHEMA, "manifest: schemaVersion")
    require(isinstance(manifest["datasetVersion"], str) and bool(manifest["datasetVersion"]), "manifest: datasetVersion")
    require(utc(manifest["createdAt"]) == manifest["createdAt"], "manifest: createdAt")
    require(bool(re.fullmatch(r"sha256:[0-9a-f]{64}", manifest["sourceSnapshot"])), "manifest: sourceSnapshot")
    for name, rows in [("materials", materials), ("chunks", chunks)]:
        require(manifest[name + "Count"] == len(rows), f"{name}: count mismatch")
        require(manifest[name + "Sha256"] == sha256((folder / (name + ".jsonl")).read_bytes()), f"{name}: file hash mismatch")
    lookup = {}
    for line, row in enumerate(materials, 1):
        prefix = f"materials.jsonl:{line}"
        expected = {"schemaVersion", "materialId", "sourceType", "sourceId", "title", "description", "url",
                    "isPaid", "answerAllowed", "externalMetadataAllowed", "duration", "topics", "industries",
                    "parentMaterialIds", "sourceUpdatedAt"}
        if row.get("sourceType") == "COURSE" or "sourceCost" in row:
            expected.add("sourceCost")
        require(set(row) == expected, prefix + ": missing or unknown fields")
        UUID(row["sourceId"])
        require(row["materialId"] == material_id(row["sourceType"], row["sourceId"]), prefix + ": invalid UUIDv5")
        require(row["materialId"] not in lookup, prefix + ": duplicate ID")
        lookup[row["materialId"]] = row
        require(row["schemaVersion"] == SCHEMA and row["sourceType"] in KINDS.values(), prefix + ": schema/type")
        for key in ("isPaid", "answerAllowed", "externalMetadataAllowed"):
            require(type(row[key]) is bool, prefix + ": " + key)
        require(not (row["isPaid"] and row["answerAllowed"]), prefix + ": paid answer text")
        require(row["externalMetadataAllowed"] is False, prefix + ": external permission not established by this pipeline")
        require(canonical_url(row["url"]) == row["url"], prefix + ": invalid URL")
        for key, limit in [("title", 250), ("description", 1000), ("duration", 100)]:
            value = row[key]
            require((value is None and key != "title") or (isinstance(value, str) and 1 <= len(value) <= limit), prefix + ": " + key)
        for key in ("topics", "industries", "parentMaterialIds"):
            require(isinstance(row[key], list) and all(isinstance(v, str) for v in row[key]), prefix + ": " + key)
        if row["sourceType"] == "COURSE" or "sourceCost" in row:
            cost = row["sourceCost"]
            require(cost is None or (type(cost) is int and cost >= 0), prefix + ": invalid cost")
            require(row["isPaid"] == (cost is not None and cost > 0), prefix + ": cost/paid conflict")
        if row["sourceUpdatedAt"] is not None:
            require(utc(row["sourceUpdatedAt"]) == row["sourceUpdatedAt"], prefix + ": timestamp")
    for row in materials:
        require(all(parent in lookup and lookup[parent]["sourceType"] == "COURSE" for parent in row["parentMaterialIds"]), "invalid parent material")
        if row["sourceType"] == "LESSON" and row["answerAllowed"]:
            require(bool(row["parentMaterialIds"]) and all(not lookup[p]["isPaid"] for p in row["parentMaterialIds"]), "lesson without free parent")
    ids, orders = set(), defaultdict(list)
    for line, row in enumerate(chunks, 1):
        prefix = f"chunks.jsonl:{line}"
        require(set(row) == {"schemaVersion", "chunkId", "materialId", "order", "heading", "text", "contentHash", "externalTextAllowed"}, prefix + ": missing or unknown fields")
        require(row["schemaVersion"] == SCHEMA, prefix + ": schemaVersion")
        mid = row["materialId"]
        require(mid in lookup and lookup[mid]["answerAllowed"] and not lookup[mid]["isPaid"], prefix + ": forbidden or missing material")
        require(type(row["order"]) is int and row["order"] >= 1, prefix + ": invalid order")
        require(row["chunkId"] == str(uuid5(CHUNK_NS, mid + ":" + str(row["order"]))), prefix + ": invalid UUIDv5")
        require(row["chunkId"] not in ids, prefix + ": duplicate ID")
        ids.add(row["chunkId"])
        orders[mid].append(row["order"])
        require(isinstance(row["text"], str) and 30 <= len(row["text"]) <= MAX_CHARS, prefix + ": text length")
        require(row["heading"] is None or (isinstance(row["heading"], str) and len(row["heading"]) <= 250), prefix + ": heading")
        require(type(row["externalTextAllowed"]) is bool, prefix + ": permission")
        require(row["externalTextAllowed"] is False, prefix + ": external permission not established by this pipeline")
        require(row["contentHash"] == sha256(row["text"].encode("utf-8")), prefix + ": text hash")
    for mid, values in orders.items():
        require(sorted(values) == list(range(1, len(values) + 1)), "chunk order gap: " + mid)
    require(all(bool(orders[row["materialId"]]) == row["answerAllowed"] for row in materials), "answerAllowed/chunks mismatch")
    return {"materialsCount": len(materials), "chunksCount": len(chunks), "valid": True}


def prepare(snapshot, version, now):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", version):
        raise ValueError("Некорректная версия выгрузки")
    destination = ROOT / "data" / "processed" / version
    if destination.exists():
        raise ValueError("Версия уже существует. Используйте новое имя; существующие данные не перезаписываются.")
    payload = snapshot.read_bytes()
    data = json.loads(payload)
    materials, chunks, report = build(data, now)
    if not materials or not chunks:
        raise ValueError("Пустой корпус: проверьте права доступа, статусы и ссылки")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".preparing-", dir=destination.parent))
    try:
        write_jsonl(temporary / "materials.jsonl", materials)
        write_jsonl(temporary / "chunks.jsonl", chunks)
        manifest = {
            "schemaVersion": SCHEMA, "datasetVersion": version, "createdAt": now,
            "sourceSnapshot": "sha256:" + sha256(payload),
            "materialsCount": len(materials), "chunksCount": len(chunks),
            "materialsSha256": sha256((temporary / "materials.jsonl").read_bytes()),
            "chunksSha256": sha256((temporary / "chunks.jsonl").read_bytes()),
        }
        dump_json(temporary / "manifest.json", manifest)
        dump_json(temporary / "report.json", report)
        result = validate(temporary)
        temporary.rename(destination)
    except BaseException:
        shutil.rmtree(temporary)
        raise
    print(json.dumps({"output": str(destination), **result, "materialsByType": report["materialsByType"],
                      "excludedByReason": report["excludedByReason"], "warnings": report["warnings"]}, ensure_ascii=False, indent=2))


def tokens(text):
    return re.findall(r"[a-zа-яё0-9]+", text.casefold().replace("ё", "е"))


def search(folder, query, limit):
    """Простой BM25 baseline. Не семантический поиск и не доказательство качества RAG."""
    validate(folder)
    materials = {r["materialId"]: r for r in read_jsonl(folder / "materials.jsonl")}
    chunks = read_jsonl(folder / "chunks.jsonl")
    documents = [tokens(materials[c["materialId"]]["title"] + " " + c["text"]) for c in chunks]
    df = Counter(term for doc in documents for term in set(doc))
    avg = sum(map(len, documents)) / max(1, len(documents))
    query_terms = set(tokens(query))
    ranked = []
    for chunk, doc in zip(chunks, documents):
        counts = Counter(doc)
        score = 0.0
        for term in query_terms & counts.keys():
            idf = math.log(1 + (len(documents) - df[term] + 0.5) / (df[term] + 0.5))
            freq = counts[term]
            score += idf * freq * 2.5 / (freq + 1.5 * (0.25 + 0.75 * len(doc) / avg))
        if score:
            ranked.append((score, chunk))
    ranked.sort(key=lambda item: (-item[0], item[1]["chunkId"]))
    result, seen = [], set()
    for score, chunk in ranked:
        if chunk["materialId"] in seen:
            continue
        seen.add(chunk["materialId"])
        m = materials[chunk["materialId"]]
        result.append({"score": round(score, 3), "title": m["title"], "url": m["url"],
                       "chunkId": chunk["chunkId"], "excerpt": chunk["text"][:500]})
        if len(result) >= limit:
            break
    print(json.dumps(result, ensure_ascii=False, indent=2))


def timeout_seconds(value):
    seconds = int(value)
    if not 1 <= seconds <= 600:
        raise argparse.ArgumentTypeError("timeout должен быть от 1 до 600 секунд")
    return seconds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    export = sub.add_parser("export", help="Прочитать alfa_source; не изменяет БД")
    export.add_argument("--output", type=Path, default=ROOT / "data/source/ml_snapshot.json")
    backup = sub.add_parser("import-backup", help="Снимок из .backup или ZIP без PostgreSQL/Docker")
    backup.add_argument("source", type=Path)
    backup.add_argument("--output", type=Path, default=ROOT / "data/source/ml_snapshot.json")
    audit = sub.add_parser("audit", help="Сводка структуры локального снимка")
    audit.add_argument("snapshot", type=Path)
    prep = sub.add_parser("prepare", help="Очистить и записать проверенную новую версию")
    prep.add_argument("--snapshot", type=Path, default=ROOT / "data/source/ml_snapshot.json")
    prep.add_argument("--version", required=True)
    prep.add_argument("--as-of", help="Фиксированный UTC-момент для воспроизводимости")
    val = sub.add_parser("validate", help="Проверить контракт и хеши")
    val.add_argument("dataset", type=Path)
    find = sub.add_parser("search", help="Локальный лексический поиск без API")
    find.add_argument("dataset", type=Path)
    find.add_argument("query")
    find.add_argument("--limit", type=int, default=3, choices=range(1, 21))
    index = sub.add_parser("index", help="Создать локальный семантический индекс через Ollama")
    index.add_argument("dataset", type=Path)
    index.add_argument("--embedding-model", default="embeddinggemma:300m")
    index.add_argument("--batch-size", type=int, default=16, choices=range(1, 65))
    retrieve = sub.add_parser("retrieve", help="Найти источники и карточки по смыслу")
    retrieve.add_argument("dataset", type=Path)
    retrieve.add_argument("query")
    retrieve.add_argument("--limit", type=int, default=5, choices=range(1, 9))
    retrieve.add_argument("--min-score", type=float, default=0.35)
    answer = sub.add_parser("ask", help="Получить ответ локальной модели по найденным источникам")
    answer.add_argument("dataset", type=Path)
    answer.add_argument("question")
    answer.add_argument("--chat-model", default="qwen3.5:9b-q4_K_M")
    answer.add_argument("--context", type=Path, help="Локальный JSON с profile/history/currentPlan")
    answer.add_argument("--min-score", type=float, default=0.35)
    answer.add_argument("--timeout", type=timeout_seconds, default=60, metavar="SECONDS")
    chat = sub.add_parser("chat", help="Диалог в терминале с историей и источниками")
    chat.add_argument("dataset", type=Path)
    chat.add_argument("--chat-model", default="qwen3.5:9b-q4_K_M")
    chat.add_argument("--context", type=Path)
    chat.add_argument("--min-score", type=float, default=0.35)
    chat.add_argument("--timeout", type=timeout_seconds, default=60, metavar="SECONDS")
    evaluate = sub.add_parser("evaluate", help="Recall/HitRate поиска по размеченному JSONL")
    evaluate.add_argument("dataset", type=Path)
    evaluate.add_argument("cases", type=Path)
    evaluate.add_argument("--limit", type=int, default=5, choices=range(1, 9))
    evaluate.add_argument("--min-score", type=float, default=0.35)
    for command in [retrieve, answer, chat, evaluate]:
        command.add_argument("--strategy", choices=["dense", "bm25", "hybrid", "hybrid_dense"], default="hybrid_dense")
        command.add_argument("--max-per-material", type=int, choices=range(1, 6), default=2)
        command.add_argument("--rerank", action="store_true", help="Экспериментальный локальный reranker")
    for command in [answer, chat]:
        command.add_argument("--answer-mode", choices=["generative", "evidence", "extracts"], default="extracts")
        command.add_argument("--compression", choices=["none", "window"], default="none")
        command.add_argument("--now", help="Серверное время UTC для воспроизводимой проверки, например 2026-11-15T10:00:00Z")
    answer.add_argument("--operation", choices=["consultation", "generate_plan"], default="consultation")
    args = parser.parse_args()
    if args.command == "import-backup":
        from import_backup import import_backup
        print(json.dumps(import_backup(args.source, args.output), ensure_ascii=False, indent=2))
    elif args.command == "chat":
        from local_chat import chat
        chat(args.dataset, args.chat_model, args.context, args.min_score, args.timeout,
             args.strategy, args.max_per_material, args.rerank, args.answer_mode, args.compression, args.now)
    elif args.command == "export":
        output = args.output.resolve()
        if not output.is_relative_to((ROOT / "data/source").resolve()):
            raise ValueError("Исходные данные можно сохранять только внутри data/source/")
        export_snapshot(output)
    elif args.command == "audit":
        data = json.loads(args.snapshot.read_text(encoding="utf-8"))
        print(json.dumps({"counts": {k: len(v) for k, v in data.items()},
                          "statuses": data["Statuses"], "courseTypes": data["CourseTypes"]}, ensure_ascii=False, indent=2))
    elif args.command == "prepare":
        now = utc(args.as_of) if args.as_of else datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        prepare(args.snapshot, args.version, now)
    elif args.command == "validate":
        print(json.dumps(validate(args.dataset), ensure_ascii=False, indent=2))
    elif args.command == "search":
        search(args.dataset, args.query, args.limit)
    else:
        from rag_runtime import Ollama, Retriever, ask, evaluate as evaluate_search, index_dataset, load_context
        client = Ollama()
        if args.command == "index":
            result = index_dataset(args.dataset, client, args.embedding_model, args.batch_size)
        else:
            retriever = Retriever(args.dataset, client)
            retriever.strategy, retriever.max_per_material, retriever.llm_rerank = args.strategy, args.max_per_material, args.rerank
            if args.command == "retrieve":
                result = retriever.retrieve(args.query, args.limit, args.min_score)
            elif args.command == "ask":
                result = ask(retriever, args.question, client, args.chat_model, load_context(args.context), args.min_score, args.timeout,
                             args.answer_mode, args.compression, args.now, args.operation)
            else:
                result = evaluate_search(retriever, args.cases, args.limit, args.min_score)
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Ошибка: {error}") from error

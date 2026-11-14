"""Чтение разрешённого подмножества PGDMP без запуска SQL и PostgreSQL."""

from datetime import datetime
import json
from pathlib import Path
import re
import tempfile
import zipfile

import rag


def convert_value(value, sql_type):
    if value is None:
        return None
    if sql_type == "boolean":
        if value not in {"t", "f"}:
            raise ValueError("Некорректное значение boolean в дампе")
        return value == "t"
    if sql_type in {"integer", "smallint", "bigint"}:
        return int(value)
    if sql_type == "timestamp with time zone" and value not in {"infinity", "-infinity"}:
        return datetime.fromisoformat(value).isoformat().replace("+00:00", "Z")
    return value


def read_snapshot(dump):
    entries = {(e.desc, e.tag): e for e in dump.entries if e.namespace == "public"}
    result = {}
    for table, fields in rag.TABLES.items():
        definition = entries[("TABLE", table)].defn
        types = dict(re.findall(
            r'^\s+"([^"]+)"\s+(timestamp with time zone|character varying(?:\(\d+\))?|public\.citext|uuid|boolean|integer|smallint|bigint|text)\b',
            definition, re.M))
        copy_stmt = entries[("TABLE DATA", table)].copy_stmt
        match = re.search(r'\((.*?)\) FROM stdin;', copy_stmt, re.S)
        if not match:
            raise ValueError(f"Не распознан COPY для {table}")
        columns = re.findall(r'"([^"]+)"', match[1])
        selected = fields.split()
        if not set(selected) <= set(columns) or not set(selected) <= set(types):
            raise ValueError(f"Схема таблицы {table} не соответствует ожидаемым полям")
        positions = {field: columns.index(field) for field in selected}
        rows = []
        for row in dump.table_data("public", table):
            if len(row) != len(columns):
                raise ValueError(f"Неверное число колонок в {table}")
            rows.append({f: convert_value(row[positions[f]], types[f]) for f in selected})
        # Канонический порядок, независимый от порядка COPY.
        rows.sort(key=lambda row: json.dumps(row, ensure_ascii=False, sort_keys=True))
        result[table] = rows
    return result


def import_backup(source, output):
    try:
        import pgdumplib
    except ImportError as error:
        raise ValueError("Установите зависимости: python -m pip install -r ml/requirements.txt") from error
    source, output = Path(source).resolve(), Path(output).resolve()
    if not output.is_relative_to((rag.ROOT / "data/source").resolve()):
        raise ValueError("Снимок можно сохранять только внутри data/source/")
    if output.exists():
        raise ValueError(f"Файл уже существует: {output}")
    with tempfile.TemporaryDirectory(prefix="alfa-import-") as temporary:
        if zipfile.is_zipfile(source):
            with zipfile.ZipFile(source) as archive:
                backups = [n for n in archive.namelist() if n.endswith(".backup")]
                if len(backups) != 1:
                    raise ValueError("В ZIP должен находиться ровно один .backup")
                backup = Path(temporary) / "source.backup"
                # Не извлекаем произвольные пути из архива.
                with archive.open(backups[0]) as src, backup.open("wb") as dest:
                    import shutil
                    shutil.copyfileobj(src, dest)
        else:
            backup = source
        data = read_snapshot(pgdumplib.load(backup))
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as stream:
        json.dump(data, stream, ensure_ascii=False)
        stream.write("\n")
    return {"snapshot": str(output), "counts": {k: len(v) for k, v in data.items()}}

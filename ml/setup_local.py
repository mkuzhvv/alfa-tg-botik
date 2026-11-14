#!/usr/bin/env python3
"""Повторяемая подготовка локального стенда из предоставленного ZIP/PGDMP."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess

import rag
from import_backup import import_backup
from rag_runtime import Ollama, Retriever, index_dataset


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--version", default="2026-11-13-local")
    parser.add_argument("--chat-model", default="qwen3.5:9b-q4_K_M")
    parser.add_argument("--embedding-model", default="embeddinggemma:300m")
    parser.add_argument("--data-only", action="store_true", help="Подготовить корпус без загрузки моделей")
    parser.add_argument("--retrieval-only", action="store_true", help="Подготовить корпус и индекс без модели генерации")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", args.version):
        raise ValueError("Некорректная версия")
    snapshot = rag.ROOT / "data/source" / f"snapshot-{args.version}.json"
    provenance = snapshot.with_suffix(".source.sha256")
    source_hash = rag.sha256(args.source.read_bytes())
    dataset = rag.ROOT / "data/processed" / args.version
    if snapshot.exists() and (not provenance.exists() or provenance.read_text().strip() != source_hash):
        raise ValueError("Для этой версии использован другой/неподтверждённый архив; укажите новую --version")
    if not snapshot.exists():
        print(import_backup(args.source, snapshot), flush=True)
        provenance.write_text(source_hash + "\n")
    if not dataset.exists():
        rag.prepare(snapshot, args.version, datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"))
    rag.validate(dataset)
    manifest = json.loads((dataset / "manifest.json").read_text())
    if manifest["sourceSnapshot"] != "sha256:" + rag.sha256(snapshot.read_bytes()):
        raise ValueError("Корпус подготовлен из другого снимка; укажите новую --version")
    if args.data_only:
        return
    client = Ollama()
    client.request("/api/version")
    def ensure_model(model):
        names = {m["name"] for m in client.request("/api/tags").get("models", [])}
        if model not in names:
            print(f"Загрузка {model}…", flush=True)
            subprocess.run(["ollama", "pull", model], check=True)
        client.model(model)
    ensure_model(args.embedding_model)
    if not (dataset / "semantic.sqlite").exists():
        print(index_dataset(dataset, client, args.embedding_model, batch_size=16), flush=True)
    else:
        retriever = Retriever(dataset, client)
        if retriever.config["embeddingModel"]["name"] != args.embedding_model:
            raise ValueError("Индекс создан другой embedding-моделью; укажите новую --version")
        if retriever.config["embeddingModel"]["digest"] != client.model(args.embedding_model)["digest"]:
            raise ValueError("Веса embedding-модели изменились; укажите новую --version")
    if args.retrieval_only:
        print(f"Поиск готов: .venv/bin/python ml/rag.py retrieve data/processed/{args.version} 'Ваш вопрос'")
        return
    ensure_model(args.chat_model)
    if args.chat_model.startswith("qwen3.5:"):
        from token_budget import ensure_tokenizer
        ensure_tokenizer()
    print(f"Готово. Запустите из корня проекта:\n.venv/bin/python ml/rag.py chat data/processed/{args.version} --chat-model {args.chat_model}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Ошибка: {error}") from error

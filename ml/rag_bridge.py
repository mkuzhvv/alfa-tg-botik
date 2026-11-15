#!/usr/bin/env python3
"""Последовательный JSONL worker для Java ProcessBuilder. HTTP-порт не открывает."""
import argparse
import json
from pathlib import Path
import re
import sys

import rag
from rag_runtime import CHAT_MODEL, Ollama, RagError, Retriever, ask, load_context

PROTOCOL_VERSION = "1"
MAX_REQUEST_BYTES = 512 * 1024


def invalid(message):
    raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            invalid("JSON содержит повторные поля")
        result[key] = value
    return result


def decode_request(raw):
    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                      parse_constant=lambda _: invalid("JSON содержит нечисловую константу"))


def request_id(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 128 or re.search(r"[\x00-\x1f\x7f]", value):
        invalid("requestId должен быть строкой от 1 до 128 символов без управляющих символов")
    return value


def validate_request(value):
    allowed = {"schemaVersion", "requestId", "message", "profile", "history", "currentPlan", "now", "operation"}
    if not isinstance(value, dict) or set(value)-allowed:
        invalid("Ожидается JSON-объект с полями протокола RAG")
    rid = request_id(value.get("requestId"))
    if value.get("schemaVersion", rag.SCHEMA) != rag.SCHEMA:
        invalid("Не поддерживается schemaVersion")
    if value.get("operation", "consultation") not in {"consultation", "generate_plan"}:
        invalid("Неизвестная operation")
    if "now" in value:
        from feature_contract import utc_now
        if not isinstance(value["now"], str):
            invalid("now должен быть строкой UTC")
        utc_now(value["now"])
    message = value.get("message")
    if not isinstance(message, str) or not 1 <= len(message.strip()) <= 4000:
        invalid("message должен содержать от 1 до 4000 символов")
    try:
        context = load_context({key: value[key] for key in ["profile", "history", "currentPlan"] if key in value})
    except RagError as error:
        invalid(str(error))
    return rid, message, context


def error_response(rid, code, message):
    return {"requestId": rid, "ok": False, "error": {"code": code, "message": message}}


def handle_request(value, retriever, client, model=CHAT_MODEL, timeout=60, ask_fn=ask):
    rid = None
    if isinstance(value, dict):
        try:
            rid = request_id(value.get("requestId"))
        except ValueError:
            pass
    try:
        rid, message, context = validate_request(value)
    except (ValueError, TypeError) as error:
        return error_response(rid, "INVALID_REQUEST", str(error))
    try:
        kwargs = {"timeout": timeout}
        if "now" in value:
            kwargs["now"] = value["now"]
        if "operation" in value:
            kwargs["operation"] = value["operation"]
        result = ask_fn(retriever, message, client, model, context, **kwargs)
    except (RagError, ValueError, KeyError, OSError):
        # Не отражаем вопрос, профиль, путь и тело ответа модели в ошибке протокола.
        return error_response(rid, "RAG_ERROR", "Не удалось выполнить консультацию. Проверьте локальную Ollama, модели и индекс.")
    except Exception as error:
        print(f"RAG bridge internal error: {type(error).__name__}", file=sys.stderr, flush=True)
        return error_response(rid, "INTERNAL_ERROR", "Внутренняя ошибка RAG worker.")
    return {"requestId": rid, "ok": True, "result": result}


def emit(output, value):
    output.write(json.dumps(value, ensure_ascii=False, separators=(",", ":"))+"\n")
    output.flush()


def serve(input_stream, output, handler):
    while True:
        raw = input_stream.readline(MAX_REQUEST_BYTES+1)
        if not raw:
            return
        if len(raw) > MAX_REQUEST_BYTES:
            while raw and not raw.endswith(b"\n"):
                raw = input_stream.readline(MAX_REQUEST_BYTES+1)
            emit(output, error_response(None, "INVALID_REQUEST", "Размер строки запроса превышает 512 КиБ"))
            continue
        if not raw.strip():
            continue
        try:
            value = decode_request(raw)
        except (ValueError, UnicodeError, RecursionError):
            emit(output, error_response(None, "INVALID_REQUEST", "Нужен корректный UTF-8 JSON в одной строке"))
            continue
        emit(output, handler(value))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--chat-model", default=CHAT_MODEL)
    parser.add_argument("--timeout", type=rag.timeout_seconds, default=60)
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
    try:
        client = Ollama()
        retriever = Retriever(args.dataset, client)
        embedding = retriever.config["embeddingModel"]
        if client.model(embedding["name"])["digest"] != embedding["digest"]:
            raise RagError("Embedding-модель изменилась")
        client.model(args.chat_model)
        manifest = json.loads((args.dataset/"manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError, KeyError, RagError):
        emit(sys.stdout, {"event": "fatal", "protocolVersion": PROTOCOL_VERSION,
                          "error": {"code": "STARTUP_ERROR", "message": "Проверьте корпус, индекс и локальные модели Ollama; выполните setup_local.py."}})
        return 1
    emit(sys.stdout, {"event": "ready", "protocolVersion": PROTOCOL_VERSION,
                      "schemaVersion": rag.SCHEMA, "datasetVersion": manifest["datasetVersion"],
                      "chatModel": args.chat_model, "embeddingModel": embedding["name"]})
    serve(sys.stdin.buffer, sys.stdout, lambda value: handle_request(value, retriever, client, args.chat_model, args.timeout))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

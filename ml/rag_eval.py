"""Локальная воспроизводимая оценка. Реальные вопросы/тексты остаются в data/."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib.util
import inspect
import json
from pathlib import Path
import sqlite3
import statistics
import sys
import time

import rag


class CachedOllama:
    """Кэш только embeddings, с digest весов. Генерация никогда не кэшируется."""
    def __init__(self, client, path):
        self.client = client
        self.path = path
        with sqlite3.connect(path) as db:
            db.execute("CREATE TABLE IF NOT EXISTS embeddings (key TEXT PRIMARY KEY, value TEXT)")

    @property
    def deadline(self):
        return self.client.deadline

    @deadline.setter
    def deadline(self, value):
        self.client.deadline = value

    def model(self, name):
        return self.client.model(name)

    def request(self, *args, **kwargs):
        return self.client.request(*args, **kwargs)

    def embed(self, model, texts):
        digest = self.model(model)["digest"]
        key = hashlib.sha256(json.dumps([model, digest, texts], ensure_ascii=False).encode()).hexdigest()
        with sqlite3.connect(self.path) as db:
            row = db.execute("SELECT value FROM embeddings WHERE key=?", (key,)).fetchone()
            if row:
                return json.loads(row[0])
        result = self.client.embed(model, texts)
        with sqlite3.connect(self.path) as db:
            db.execute("INSERT OR REPLACE INTO embeddings VALUES (?,?)", (key, json.dumps(result)))
        return result


def validate_cases(cases, retriever):
    ids = set()
    groups = {}
    for case in cases:
        if case["id"] in ids:
            raise ValueError("Повторный ID примера")
        ids.add(case["id"])
        if case["expectedStatus"] not in {"ANSWER", "CLARIFY", "NOT_FOUND"}:
            raise ValueError("Неизвестный эталонный статус")
        if case["split"] not in {"dev", "test"}:
            raise ValueError("Неизвестная часть набора")
        if case["group"] in groups and groups[case["group"]] != case["split"]:
            raise ValueError("Одна группа попала в dev и test")
        groups[case["group"]] = case["split"]
        expected = case["relevantChunkIds"]
        if case["expectedStatus"] == "ANSWER" and not expected:
            raise ValueError("Для ANSWER нужны размеченные доказательства")
        for cid in expected:
            chunk = retriever.chunks[cid]
            material = retriever.materials[chunk["materialId"]]
            if not material["answerAllowed"] or material["isPaid"]:
                raise ValueError("Эталон использует запрещённый текст")
            if chunk["materialId"] not in case["relevantMaterialIds"]:
                raise ValueError("Эталонный материал не соответствует фрагменту")
        quote = case.get("referenceQuote")
        if expected and (not quote or not any(quote in retriever.chunks[cid]["text"] for cid in expected)):
            raise ValueError("Эталонная цитата отсутствует в разрешённом фрагменте")
    return {"cases": len(cases), "groups": len(groups)}


def load_cases(path, retriever):
    manifest_path = path.parent / "manifest.json"
    if manifest_path.exists() and path.stem in {"dev", "test"}:
        manifest = json.loads(manifest_path.read_text())
        all_cases = []
        for split in ["dev", "test"]:
            file = path.parent / f"{split}.jsonl"
            if rag.sha256(file.read_bytes()) != manifest["sha256"][split]:
                raise ValueError("Набор изменён после фиксации manifest")
            split_cases = rag.read_jsonl(file)
            if len(split_cases) != manifest["counts"][split] or any(c["split"] != split for c in split_cases):
                raise ValueError("Неверная часть или размер набора")
            all_cases.extend(split_cases)
        validate_cases(all_cases, retriever)
    cases = rag.read_jsonl(path)
    validate_cases(cases, retriever)
    return cases


def retrieval_metrics(case, retrieved, sent, sent_sources=None):
    expected = set(case["relevantChunkIds"])
    materials = set(case["relevantMaterialIds"])
    hits = retrieved["sources"]
    positions = [i for i, hit in enumerate(hits, 1) if hit["chunkId"] in expected]
    return {"materialHit@5": bool(materials & {h["materialId"] for h in hits}),
            "chunkHit@5": bool(positions),
            "chunkRecall@5": len(expected & {h["chunkId"] for h in hits}) / len(expected) if expected else None,
            "mrr@5": 1 / positions[0] if positions else 0,
            "sentGoldChunk": bool(expected & set(sent)),
            "sentReferenceEvidence": any(case.get("referenceQuote") and case["referenceQuote"] in h["text"]
                                         for h in (sent_sources if sent_sources is not None else hits) if h["chunkId"] in sent)}


def percentile(values, fraction):
    values = sorted(values)
    if not values:
        return None
    return values[min(len(values)-1, int((len(values)-1)*fraction + .5))]


def summarize(rows):
    successful = [r for r in rows if "error" not in r]
    answerable = [r for r in rows if r["expectedStatus"] == "ANSWER"]
    result = {"requests": len(rows), "technicalErrors": len(rows)-len(successful),
              "latencySeconds": {"p50": percentile([r["seconds"] for r in rows], .5),
                                 "p95": percentile([r["seconds"] for r in rows], .95)}}
    for key in ["materialHit@5", "chunkHit@5", "chunkRecall@5", "mrr@5", "sentGoldChunk", "sentReferenceEvidence"]:
        result[key] = sum(r.get("retrieval", {}).get(key, 0) or 0 for r in answerable) / len(answerable) if answerable else None
    generated = [r for r in rows if "statusCorrect" in r or "error" in r]
    if generated:
        result["statusAccuracy"] = sum(r.get("statusCorrect", False) for r in generated) / len(generated)
        result["byKind"] = {}
        for kind in sorted({r["kind"] for r in generated}):
            selected = [r for r in generated if r["kind"] == kind]
            result["byKind"][kind] = {"n": len(selected), "statusAccuracy": sum(r.get("statusCorrect", False) for r in selected)/len(selected)}
        concepts = [r["conceptCoverageProxy"] for r in successful if "conceptCoverageProxy" in r]
        result["conceptCoverageProxy"] = statistics.mean(concepts) if concepts else None
        result["conceptCoverageNote"] = "Lexical proxy, not factual correctness or faithfulness."
        actions = [r for r in generated if r["kind"] == "action"]
        if actions:
            result["unsupportedActionsHandledHonestly"] = sum(
                r.get("result", {}).get("diagnostics", {}).get("route") == "unsupported_action"
                and r.get("result", {}).get("response", {}).get("planProposal") is None
                and r.get("result", {}).get("response", {}).get("reminderProposal") is None
                for r in actions)/len(actions)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("cases", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runtime", type=Path)
    parser.add_argument("--strategy", default="dense")
    parser.add_argument("--mode", choices=["retrieval", "generation"], default="retrieval")
    parser.add_argument("--answer-mode", choices=["generative", "evidence", "extracts"], default="generative")
    parser.add_argument("--compression", choices=["none", "window"], default="none")
    parser.add_argument("--rerank", action="store_true")
    parser.add_argument("--oracle", action="store_true", help="Только диагностика генерации с эталонным контекстом")
    parser.add_argument("--min-score", type=float, default=.45)
    parser.add_argument("--max-per-material", type=int, default=1)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--one-per-group", action="store_true")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    if args.oracle and args.mode != "generation":
        raise ValueError("Oracle — диагностика генерации, не результат поиска")
    if args.runtime:
        spec = importlib.util.spec_from_file_location("evaluated_runtime", args.runtime)
        runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runtime)
    else:
        import rag_runtime as runtime
    args.output.parent.mkdir(parents=True, exist_ok=True)
    client = CachedOllama(runtime.Ollama(), args.output.parent / "query_embeddings.sqlite")
    retriever = runtime.Retriever(args.dataset, client)
    if hasattr(retriever, "strategy"):
        retriever.strategy = args.strategy
        retriever.max_per_material = args.max_per_material
        retriever.llm_rerank = args.rerank
    elif args.strategy != "dense":
        raise ValueError("Выбранный runtime не поддерживает эту стратегию")
    cases = load_cases(args.cases, retriever)
    if args.one_per_group:
        seen = set()
        cases = [c for c in cases if not (c["group"] in seen or seen.add(c["group"]))]
    if args.limit:
        cases = cases[:args.limit]
    runtime_path = args.runtime or Path(runtime.__file__)
    dependencies = [Path(__file__), Path(rag.__file__)]
    dependencies += [Path(__file__).parent/name for name in ["answer_policy.py", "lexical.py", "token_budget.py", "context_selection.py", "rerank.py", "calculation.py", "requirements.txt"]]
    config = {"mode": args.mode, "strategy": args.strategy, "minScore": args.min_score,
              "answerMode": args.answer_mode,
              "compression": args.compression,
              "rerank": args.rerank,
              "oracleGoldContext": args.oracle,
              "maxPerMaterial": args.max_per_material, "casesSha256": rag.sha256(args.cases.read_bytes()),
              "runtimeSha256": rag.sha256(runtime_path.read_bytes()),
              "dependenciesSha256": {p.name: rag.sha256(p.read_bytes()) for p in dependencies},
              "promptVersion": runtime.PROMPT_VERSION, "model": client.model(runtime.CHAT_MODEL),
              "indexSha256": rag.sha256((args.dataset / "semantic.sqlite").read_bytes()),
              "caseIds": [c["id"] for c in cases]}
    rows = []
    if args.resume and args.output.exists():
        old = json.loads(args.output.read_text())
        if old["configuration"] != config:
            raise ValueError("Нельзя продолжить оценку после изменения кода/модели/набора")
        rows = old["rows"]
    done = {r["id"] for r in rows}
    for case in cases:
        if case["id"] in done:
            continue
        started = time.monotonic()
        row = {"id": case["id"], "group": case["group"], "question": case["question"],
               "kind": case["kind"], "expectedStatus": case["expectedStatus"]}
        if args.oracle:
            def oracle_retrieve(query, limit=5, min_score=.45, example=case):
                sources = []
                for cid in example["relevantChunkIds"][:limit]:
                    chunk = retriever.chunks[cid]
                    material = retriever.materials[chunk["materialId"]]
                    sources.append({**chunk, "title": material["title"], "url": material["url"], "score": 1.0})
                cards = [retriever.materials[mid] for mid in example["relevantMaterialIds"]]
                return {"sources": sources, "catalog": cards, "catalogScores": {m["materialId"]: 1.0 for m in cards}}
            retriever.retrieve = oracle_retrieve
        try:
            if args.mode == "generation":
                kwargs = {"context": case["context"], "min_score": args.min_score}
                if "answer_mode" in inspect.signature(runtime.ask).parameters:
                    kwargs["answer_mode"] = args.answer_mode
                    kwargs["compression"] = args.compression
                result = runtime.ask(retriever, case["question"], client, **kwargs)
                row["result"] = result
                row["statusCorrect"] = result["response"]["status"] == case["expectedStatus"]
                text = result["response"]["answer"].casefold()
                concepts = case["requiredConcepts"]
                if concepts:
                    row["conceptCoverageProxy"] = sum(c.casefold() in text for c in concepts)/len(concepts)
                sent = result["diagnostics"].get("sourceChunkIdsSent", [])
                hits = [{**retriever.chunks[h["chunkId"]]} for h in result["diagnostics"].get("sources", [])]
                spans = result["diagnostics"].get("sourceSpansSent", [])
                sent_sources = [{"chunkId": s["chunkId"], "text": retriever.chunks[s["chunkId"]]["text"][s["offsets"][0]:s["offsets"][1]]} for s in spans] if spans else None
                row["retrieval"] = retrieval_metrics(case, {"sources": hits}, sent, sent_sources)
            else:
                query = case["question"]
                prior = [h["text"] for h in case["context"]["history"] if h["role"] == "USER"]
                if prior and len(rag.tokens(query)) < 7:
                    query = prior[-1][:600] + "\n" + query
                retrieved = retriever.retrieve(query, min_score=args.min_score)
                from token_budget import local_token_counter
                kwargs = {} if "answer_mode" not in inspect.signature(runtime.make_input).parameters else {"answer_mode": args.answer_mode,
                          "compression": args.compression,
                          "token_counter": local_token_counter(runtime.CHAT_MODEL) if args.answer_mode in {"evidence", "extracts"} else None}
                payload = runtime.make_input(case["question"], retrieved, case["context"], "2026-11-14T00:00:00Z", **kwargs)
                sent = [s["chunkId"] for s in payload["sources"]]
                row["retrieval"] = retrieval_metrics(case, retrieved, sent, payload["sources"])
                row["topChunks"] = [h["chunkId"] for h in retrieved["sources"]]
                row["sourcesSent"] = sent
                row["rerankError"] = retrieved.get("rerankError")
        except (ValueError, KeyError, RuntimeError, OSError) as error:
            row["error"] = str(error)
        row["seconds"] = round(time.monotonic()-started, 3)
        rows.append(row)
        report = {"configuration": config, "rows": rows, "summary": summarize(rows)}
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps({"progress": f"{len(rows)}/{len(cases)}", "id": row["id"],
                          "error": row.get("error"), "statusCorrect": row.get("statusCorrect")}, ensure_ascii=False), flush=True)
    print(json.dumps(summarize(rows), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

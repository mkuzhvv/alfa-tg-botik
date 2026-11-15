"""Выбор конфигурации исключительно на dev; test здесь запрещён."""
import argparse
import json
from pathlib import Path

import rag
import rag_runtime as runtime
from token_budget import local_token_counter
from rag_eval import CachedOllama, retrieval_metrics, summarize, validate_cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("cases", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cases = rag.read_jsonl(args.cases)
    if any(c["split"] != "dev" for c in cases):
        raise ValueError("Подбор параметров разрешён только на dev")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    client = CachedOllama(runtime.Ollama(), args.output.parent / "query_embeddings.sqlite")
    retriever = runtime.Retriever(args.dataset, client)
    validate_cases(cases, retriever)
    configs = [(s, threshold, count) for s in ["dense", "bm25", "hybrid", "hybrid_dense"]
               for threshold in [.35, .4, .45, .5] for count in [1, 2]]
    rows = {str(c): [] for c in configs}
    for index, case in enumerate(cases, 1):
        query = case["question"]
        prior = [h["text"] for h in case["context"]["history"] if h["role"] == "USER"]
        if prior and len(rag.tokens(query)) < 7:
            query = prior[-1][:600] + "\n" + query
        for config in configs:
            strategy, threshold, count = config
            retriever.strategy, retriever.max_per_material = strategy, count
            hits = retriever.retrieve(query, min_score=threshold)
            payload = runtime.make_input(case["question"], hits, case["context"], "2026-11-14T00:00:00Z", "evidence", local_token_counter(runtime.CHAT_MODEL))
            sent = [s["chunkId"] for s in payload["sources"]]
            rows[str(config)].append({"id": case["id"], "kind": case["kind"], "expectedStatus": case["expectedStatus"],
                                      "seconds": 0, "retrieval": retrieval_metrics(case, hits, sent),
                                      "hasSources": bool(hits["sources"]), "sourcesSent": sent})
        print(f"Retrieval sweep: {index}/{len(cases)}", flush=True)
    summaries = []
    for config in configs:
        current = rows[str(config)]
        summary = summarize(current)
        outside = [r for r in current if r["kind"] == "out_of_scope"]
        summary["outsideWithSources"] = sum(r["hasSources"] for r in outside)/len(outside)
        summary["configuration"] = {"strategy": config[0], "minScore": config[1], "maxPerMaterial": config[2]}
        # Grounding evidence first; then exact-chunk ranking; do not optimize material-only recall.
        summary["selectionScore"] = .6*summary["sentReferenceEvidence"]+.25*summary["chunkHit@5"]+.15*summary["mrr@5"]-.15*summary["outsideWithSources"]
        summaries.append(summary)
    summaries.sort(key=lambda row: (-row["selectionScore"], row["configuration"]["strategy"], -row["configuration"]["minScore"]))
    report = {"casesSha256": rag.sha256(args.cases.read_bytes()), "selectionSplit": "dev",
              "objective": ".6 sent reference evidence + .25 exact chunk Hit@5 + .15 MRR@5 - .15 out-of-scope candidates",
              "summaries": summaries, "rows": rows, "selected": summaries[0]["configuration"]}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(summaries[:5], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

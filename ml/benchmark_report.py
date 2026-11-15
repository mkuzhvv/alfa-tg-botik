"""Сводка только сохранённых измерений; разметка и результаты остаются локальными."""
import argparse
import json
from pathlib import Path

import rag
from answer_policy import evidence_passages, expand_selection
from rag_eval import summarize


def audit_extracts(rows, chunks, materials):
    answers = verified = 0
    problems = []
    for row in rows:
        result = row.get("result", {})
        response = result.get("response", {})
        if response.get("status") != "ANSWER":
            continue
        answers += 1
        diag = result["diagnostics"]
        sources = []
        for span in diag["sourceSpansSent"]:
            chunk = chunks[span["chunkId"]]
            if materials[chunk["materialId"]]["isPaid"] or not materials[chunk["materialId"]]["answerAllowed"]:
                problems.append({"id": row["id"], "error": "restricted source"})
            sources.append({"chunkId": span["chunkId"], "text": chunk["text"][span["offsets"][0]:span["offsets"][1]]})
        payload = {"sources": sources}
        try:
            rebuilt = expand_selection({"status": "ANSWER", "evidenceIds": diag["selectedEvidenceIds"],
                                        "clarification": "", "recommendations": []}, payload)
            aliases = {f"S{i}": s["chunkId"] for i, s in enumerate(sources, 1)}
            expected_ids = [aliases[i] for i in rebuilt["sourceIds"]]
            if rebuilt["answer"] != response["answer"] or expected_ids != response["sourceChunkIds"]:
                raise ValueError("Response does not match sent evidence")
            verified += 1
        except (ValueError, KeyError) as error:
            problems.append({"id": row["id"], "error": str(error)})
    return {"answers": answers, "verifiedExactExtracts": verified, "problems": problems,
            "note": "Exact textual support; this is not a semantic faithfulness or correctness score."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    parser.add_argument("dataset", type=Path)
    args = parser.parse_args()
    def read(name):
        return json.loads((args.folder / (name+".json")).read_text())
    baseline_ir, selected_ir = read("baseline-retrieval-test"), read("selected-retrieval-test")
    baseline, selected = read("baseline-generation-test"), read("selected-generation-test")
    dev = read("selected-generation-dev")
    if dev["configuration"]["runtimeSha256"] != selected["configuration"]["runtimeSha256"] or dev["configuration"]["dependenciesSha256"] != selected["configuration"]["dependenciesSha256"]:
        raise ValueError("Нельзя объединять оценки разных версий")
    rows = dev["rows"] + selected["rows"]
    if len({r["id"] for r in rows}) != len(rows):
        raise ValueError("Повторные ID при объединении")
    chunks = {r["chunkId"]: r for r in rag.read_jsonl(args.dataset / "chunks.jsonl")}
    materials = {r["materialId"]: r for r in rag.read_jsonl(args.dataset / "materials.jsonl")}
    audit = audit_extracts(rows, chunks, materials)
    report = {"holdoutBaselineRetrieval": baseline_ir["summary"], "holdoutSelectedRetrieval": selected_ir["summary"],
              "holdoutBaselineGeneration": baseline["summary"], "holdoutSelectedGeneration": selected["summary"],
              "allSelectedGeneration": summarize(rows), "extractAudit": audit}
    (args.folder / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    lines = ["# Локальная валидация RAG — 14 ноября 2026", "",
             "100 синтетических примеров с проверенными цитатами, 69 dev / 31 test. Группы источников и парафразы не пересекаются между частями. Полный корпус доступен поиску. Это агентская разметка, требующая независимой экспертной проверки.", "",
             "Параметры зафиксированы до запуска отложенной части в freeze.json. Ошибки и тайм-ауты учтены в знаменателях. Результат — лучший из проверенных вариантов на этой разметке, без гарантии глобального максимума.", "",
             "## Отложенные 31 вопрос", "", "| Показатель | Исходная версия | Выбранная версия |", "|---|---:|---:|"]
    for key, title in [("materialHit@5", "Нужный материал в топ-5"), ("chunkHit@5", "Эталонный фрагмент в топ-5"),
                       ("sentReferenceEvidence", "Эталонная цитата дошла до модели")]:
        lines.append(f"| {title} | {baseline_ir['summary'][key]:.1%} | {selected_ir['summary'][key]:.1%} |")
    for key, title in [("statusAccuracy", "Ожидаемый статус"), ("conceptCoverageProxy", "Лексическое покрытие обязательных понятий (прокси)")]:
        a, b = baseline["summary"].get(key), selected["summary"].get(key)
        lines.append(f"| {title} | {a:.1%} | {b:.1%} |" if a is not None and b is not None else f"| {title} | — | — |")
    lines += [f"| Технические ошибки | {baseline['summary']['technicalErrors']} | {selected['summary']['technicalErrors']} |",
              f"| p50 / p95, секунды | {baseline['summary']['latencySeconds']['p50']} / {baseline['summary']['latencySeconds']['p95']} | {selected['summary']['latencySeconds']['p50']} / {selected['summary']['latencySeconds']['p95']} |", "",
              "Время измерено с кэшем query embeddings; это не замер холодного запуска приложения. Лексическое покрытие не заменяет проверку фактов. Совпадение статуса ANSWER не доказывает полноту или правильность ответа.", "",
              "## Все 100 примеров выбранной версии", "", "```json", json.dumps(report["allSelectedGeneration"], ensure_ascii=False, indent=2), "```", "",
              "## Проверка происхождения основного текста", "", "```json", json.dumps(audit, ensure_ascii=False, indent=2), "```", "",
              "Программа проверяет дословность выдержек в реально отправленных фрагментах. Уместность выбранной выдержки, полнота ответа и достоверность самого источника этой проверкой не гарантируются. Объяснения рекомендаций и уточняющие вопросы формируются отдельно.", "",
              "## Ошибки и расхождения выбранной версии", ""]
    for row in rows:
        if "error" in row or not row.get("statusCorrect", False):
            lines += [f"- `{row['id']}`: {row['question']} — {row.get('error', row.get('result', {}).get('response', {}).get('status'))}"]
    lines += ["", "## Ответы на отложенные вопросы", ""]
    for row in selected["rows"]:
        lines += [f"### {row['id']}: {row['question']}", "", row.get("error") or row["result"]["response"]["answer"], ""]
        for ref in row.get("result", {}).get("references", []):
            lines += [f"Источник: [{ref['title']}]({ref['url']})"]
        lines.append("")
    (args.folder / "REPORT.md").write_text("\n".join(lines))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

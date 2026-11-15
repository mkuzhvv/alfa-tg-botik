"""Терминальный стенд: история живёт только до завершения процесса."""
import json

from rag_runtime import Ollama, RagError, Retriever, ask, load_context


def chat(dataset, model, context_path=None, min_score=0.35, timeout=60,
         strategy="hybrid_dense", max_per_material=2, rerank=False, answer_mode="extracts", compression="none", now=None):
    client = Ollama()
    retriever = Retriever(dataset, client)
    retriever.strategy, retriever.max_per_material, retriever.llm_rerank = strategy, max_per_material, rerank
    client.model(model)
    context = load_context(context_path)
    last_result = None
    print(f"Альфа-Курс · {model}\n/quit — выход, /reset — сброс истории, /sources — последние фрагменты")
    while True:
        try:
            question = input("\nВы: ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return
        if question == "/quit":
            return
        if question == "/reset":
            context["history"] = []
            last_result = None
            print("История очищена.")
            continue
        if question == "/sources":
            for ref in (last_result or {}).get("references", []):
                print(f"\n{ref['title']}\n{retriever.chunks[ref['chunkId']]['text']}")
            continue
        if not question:
            continue
        print("Ищу материалы и готовлю ответ…", flush=True)
        try:
            result = ask(retriever, question, client, model, context, min_score, timeout, answer_mode, compression, now)
        except RagError as error:
            print(f"Техническая ошибка: {error}")
            continue
        last_result = result
        response = result["response"]
        print(f"\n{response['status']}: {response['answer']}")
        for field in ["profilePatch", "planProposal", "reminderProposal"]:
            if response[field]:
                print(field+": "+json.dumps(response[field], ensure_ascii=False, indent=2))
        seen = set()
        for ref in result["references"]:
            if ref["url"] not in seen:
                print(f"Источник: {ref['title']} — {ref['url']}")
                seen.add(ref["url"])
        for card in result["recommendationCards"]:
            print(f"Рекомендация: {card['title']} — {card['recommendationReason']}\n{card['url']}")
        print(f"Время: {result['diagnostics']['elapsedSeconds']} с")
        context["history"] = (context["history"] + [
            {"role": "USER", "text": question},
            {"role": "ASSISTANT", "text": response["answer"]},
        ])[-12:]

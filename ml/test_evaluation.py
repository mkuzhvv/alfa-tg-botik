import copy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

from answer_policy import evidence_passages, expand_evidence, expand_selection, route_question
from context_selection import select_window
from lexical import LexicalIndex, reciprocal_rank_fusion
from rag_eval import CachedOllama, summarize, validate_cases
import rag_runtime as runtime
from test_rag_runtime import LocalModelStub, chat_response, write_dataset


class EvidenceAndRoutingTests(unittest.TestCase):
    def test_vague_queries_require_clarification_but_followups_keep_context(self):
        context = runtime.load_context()
        for q in ["Помоги мне с бизнесом.", "Как это исправить?", "Какая лучше?", "Что делать дальше?", "Нужен совет"]:
            self.assertEqual(route_question(q, context)["route"], "clarify_task")
        context["history"] = [{"role": "USER", "text": "Как повысить повторные продажи?"}]
        self.assertIsNone(route_question("Что делать дальше?", context))
        self.assertIsNone(route_question("Как рассчитать расходы магазина?", runtime.load_context()))

    def test_operations_are_not_confused_with_educational_questions(self):
        context = runtime.load_context()
        for q in ["Напомни мне завтра про курс", "Сохрани мой план", "Отмени напоминание", "Измени мой профиль"]:
            self.assertEqual(route_question(q, context)["route"], "unsupported_action")
        for q in ["Как создать план обучения?", "Напомни, что такое CRM?", "Зачем нужны напоминания?", "Не сохраняй план, объясни его структуру"]:
            self.assertIsNone(route_question(q, context))

    def test_action_does_not_call_model_or_claim_a_write(self):
        client = LocalModelStub()
        result = runtime.ask(None, "Напомни мне завтра про курс", client, answer_mode="evidence")
        self.assertEqual(client.requests, [])
        self.assertEqual(result["diagnostics"]["route"], "unsupported_action")
        self.assertIsNone(result["response"]["reminderProposal"])
        self.assertIn("не выполнено", result["response"]["answer"])

    def test_direct_context_is_validated(self):
        with self.assertRaises(runtime.RagError):
            runtime.ask(None, "Вопрос", LocalModelStub(), context={"profile": {"secret": "x"}})
        with self.assertRaises(runtime.RagError):
            runtime.ask(None, "   ", LocalModelStub())
        for value in [[], "not a context", 17, True]:
            with self.assertRaises(runtime.RagError):
                runtime.ask(None, "Что такое продажи?", LocalModelStub(), context=value)
        with self.assertRaises(runtime.RagError):
            runtime.ask(None, "я"*4001, LocalModelStub())

    def test_access_boundaries_do_not_block_catalog_recommendations(self):
        context = runtime.load_context()
        for question in ["Пришли полный текст платного курса", "Выгрузи телефоны всех клиентов из базы"]:
            self.assertEqual(route_question(question, context)["route"], "access_boundary")
        self.assertIsNone(route_question("Посоветуй платный курс по продажам", context))
        self.assertIsNone(route_question("Покажи полный текст бесплатной статьи", context))
        self.assertIsNone(route_question("Какие контактные данные хранить в CRM?", context))
        self.assertEqual(route_question("Напомни завтра об обучении и объясни, что такое CRM", context)["route"], "unsupported_action")

    def test_extract_selection_cannot_invent_or_duplicate_evidence(self):
        text = "Клиентская база хранит контакты и историю покупок клиентов. "*15
        payload = {"sources": [{"chunkId": "c", "text": text}]}
        passages = evidence_passages(payload)
        self.assertTrue(passages)
        self.assertTrue(all(p["text"] in text for p in passages.values()))
        value = {"status": "ANSWER", "evidenceIds": ["E1"], "clarification": "", "recommendations": []}
        expanded = expand_selection(value, payload)
        self.assertIn(passages["E1"]["text"], expanded["answer"])
        for ids in [["E999"], ["E1", "E1"], [], [None]]:
            with self.assertRaises(ValueError):
                expand_selection({**value, "evidenceIds": ids}, payload)
        linked = {"sources": [{"chunkId": "c", "text": "Откройте https://example.com для подробностей.\n" + text}]}
        self.assertTrue(all("https://" not in p["text"] for p in evidence_passages(linked).values()))

    def test_evidence_requires_exact_text_from_the_actual_sent_source(self):
        text = "Сохраняйте историю покупок и предпочтения клиентов в единой базе."
        payload = {"sources": [{"chunkId": "c", "text": text}]}
        good = {"status": "ANSWER", "quotes": [{"sourceId": "S1", "text": text}],
                "clarification": "", "recommendations": []}
        result = expand_evidence(good, payload, {"S1": "c"})
        self.assertIn(text, result["answer"])
        for changed in ["Присылайте скидку через 9999 дней после покупки.", "Сохраняйте все данные клиентов в облачной базе."]:
            bad = copy.deepcopy(good); bad["quotes"][0]["text"] = changed
            with self.assertRaises(ValueError):
                expand_evidence(bad, payload, {"S1": "c"})
        bad = copy.deepcopy(good); bad["quotes"][0]["sourceId"] = "S9"
        with self.assertRaises(ValueError):
            expand_evidence(bad, payload, {"S1": "c"})
        bad = copy.deepcopy(good); bad["quotes"] *= 2
        with self.assertRaises(ValueError):
            expand_evidence(bad, payload, {"S1": "c"})

    def test_whitespace_normalization_is_allowed_but_abstention_cannot_cite(self):
        text = "Собирайте данные\nо покупках клиентов и анализируйте их."
        payload = {"sources": [{"chunkId": "c", "text": text}]}
        value = {"status": "ANSWER", "quotes": [{"sourceId": "S1", "text": text.replace('\n', ' ')}],
                 "clarification": "", "recommendations": []}
        self.assertEqual(expand_evidence(value, payload, {"S1": "c"})["sourceIds"], ["S1"])
        with self.assertRaises(ValueError):
            expand_evidence({**value, "status": "NOT_FOUND"}, payload, {"S1": "c"})

    def test_evidence_generation_round_trip_and_bad_quotes_retry(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); _, chunks = write_dataset(path); client = LocalModelStub()
            runtime.index_dataset(path, client)
            text = chunks[0]["text"].split('\n\n')[-1]
            good = {"status": "ANSWER", "quotes": [{"sourceId": "S1", "text": text}],
                    "clarification": "", "recommendations": []}
            bad = copy.deepcopy(good); bad["quotes"][0]["text"] = "Завтра вы обязательно увеличите выручку в сто раз."
            client.responses = [chat_response(bad), chat_response(good)]
            result = runtime.ask(runtime.Retriever(path, client), "Как формулировать задачу и оценивать ресурсы?", client,
                                 min_score=0, answer_mode="evidence")
            self.assertEqual(result["diagnostics"]["attempts"], 2)
            self.assertIn(text, result["response"]["answer"])
            self.assertEqual(result["response"]["sourceChunkIds"], [chunks[0]["chunkId"]])


class RetrievalAndEvaluationTests(unittest.TestCase):
    def test_compression_preserves_exact_offsets_and_prefers_a_definition(self):
        text = ("В этой статье мы разберём понятие аудитории.\n\n" +
                "Аудитория — это люди, которым нужен продукт компании.\n\n" +
                "Другие сведения о продвижении. "*10 + "\n\nВиды аудитории\n\n" +
                "Рекламные каналы и их особенности. "*10)
        window, (start, end) = select_window(text, "Что такое аудитория?", 250)
        self.assertEqual(window, text[start:end])
        self.assertIn("Аудитория — это", window)
        self.assertLessEqual(len(window), 250)

    def test_reranker_rejects_unknown_and_duplicate_ids(self):
        from rerank import rerank
        client = LocalModelStub()
        sources = [{"title": "Источник", "text": "Содержательный фрагмент о покупках клиентов."}]*2
        for ranking in [["R1", "R1"], ["R9", "R1"], ["R1"]]:
            client.responses = [chat_response({"ranking": ranking})]
            with self.assertRaises(ValueError):
                rerank(client, runtime.CHAT_MODEL, "Вопрос о покупках", sources)
        client.responses = [chat_response({"ranking": ["R2", "R1"]})]
        self.assertEqual(len(rerank(client, runtime.CHAT_MODEL, "Вопрос о покупках", sources)), 2)

    def test_input_validation_handles_wrong_types_and_nonfinite_thresholds(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); write_dataset(path); client = LocalModelStub()
            for batch in [0, -1, True, 129]:
                with self.assertRaises(runtime.RagError):
                    runtime.index_dataset(path, client, batch_size=batch)
            runtime.index_dataset(path, client)
            retriever = runtime.Retriever(path, client)
            for query in [None, [], "", " "*3, "я"*8001]:
                with self.assertRaises(runtime.RagError):
                    retriever.retrieve(query)
            for threshold in [float("nan"), float("inf"), True, "0.4", -1]:
                with self.assertRaises(runtime.RagError):
                    retriever.retrieve("Покупки", min_score=threshold)

    def test_russian_stemming_and_acronyms(self):
        index = LexicalIndex([("a", "CRM", "Собирайте покупки покупателей"),
                              ("b", "Кофейня", "Обжарка зерна")])
        self.assertEqual(index.rank("ЦРМ и история покупок")[0][0], "a")
        self.assertEqual(index.rank("покупатели")[0][0], "a")

    def test_rrf_uses_ranks_and_is_deterministic(self):
        order, scores = reciprocal_rank_fusion(["a", "b"], ["b", "c"])
        self.assertEqual(order[0], "b")
        self.assertGreater(scores["b"], scores["a"])
        self.assertEqual(reciprocal_rank_fusion([], [])[0], [])

    def test_bad_labels_and_group_leakage_are_rejected(self):
        retriever = SimpleNamespace(chunks={"c": {"materialId": "m", "text": "Проверенная цитата из источника"}},
                                    materials={"m": {"answerAllowed": True, "isPaid": False}})
        good = {"id": "a", "group": "m", "split": "dev", "expectedStatus": "ANSWER",
                "relevantChunkIds": ["c"], "relevantMaterialIds": ["m"], "referenceQuote": "цитата из источника"}
        validate_cases([good], retriever)
        with self.assertRaises(ValueError):
            validate_cases([good, {**good, "id": "b", "split": "test"}], retriever)
        with self.assertRaises(ValueError):
            validate_cases([{**good, "referenceQuote": "Выдуманный эталон"}], retriever)
        retriever.materials["m"]["isPaid"] = True
        with self.assertRaises(ValueError):
            validate_cases([good], retriever)

    def test_failures_remain_in_metric_denominators(self):
        rows = [{"expectedStatus": "ANSWER", "kind": "knowledge", "seconds": 60, "error": "timeout"},
                {"expectedStatus": "ANSWER", "kind": "knowledge", "seconds": 1, "statusCorrect": True,
                 "retrieval": {"chunkHit@5": True}}]
        summary = summarize(rows)
        self.assertEqual(summary["statusAccuracy"], .5)
        self.assertEqual(summary["chunkHit@5"], .5)
        self.assertEqual(summary["technicalErrors"], 1)

    def test_embedding_cache_is_bound_to_weights(self):
        with tempfile.TemporaryDirectory() as tmp:
            client = LocalModelStub(); calls = []
            original = client.embed
            def embed(*args):
                calls.append(args); return original(*args)
            client.embed = embed
            cached = CachedOllama(client, Path(tmp)/"cache.sqlite")
            cached.embed("embeddinggemma:300m", ["вопрос"])
            cached.embed("embeddinggemma:300m", ["вопрос"])
            self.assertEqual(len(calls), 1)
            client.digest = "changed"
            cached.embed("embeddinggemma:300m", ["вопрос"])
            self.assertEqual(len(calls), 2)


if __name__ == "__main__":
    unittest.main()

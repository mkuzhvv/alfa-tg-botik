import copy
import json
from pathlib import Path
import tempfile
import unittest
from uuid import UUID

import rag
import rag_runtime as runtime
from feature_contract import utc_now, validate_schedule, validate_current_plan
from reminder_proposals import draft_reminder
from profile_updates import supported_change, extract_profile_patch
from test_rag import fixture
from test_rag_runtime import LocalModelStub, chat_response, write_dataset

NOW = "2026-11-15T10:00:00Z"
MID = "00000000-0000-4000-8000-000000000111"


def plan_context():
    card = {"id": MID, "title": "Учебный материал", "kind": "ARTICLE", "description": None,
            "recommendationReason": None, "isPaid": False, "duration": None,
            "url": "https://kurs.alfabank.ru/articles/synthetic/"}
    return {"id": "00000000-0000-4000-8000-000000000222", "goal": "Удержать покупателей",
            "status": "ACTIVE", "version": 1,
            "steps": [{"id": f"00000000-0000-4000-8000-{i:012d}", "position": i,
                       "title": f"Учебный этап {i}", "description": "Разобрать материал и проверить результат.",
                       "status": "DONE" if i == 1 else "TODO", "materials": [copy.deepcopy(card)]} for i in range(1, 4)]}


def context(tz="Europe/Moscow"):
    return {"profile": {"timeZone": tz} if tz else {}, "history": [], "currentPlan": None}


class ReminderTests(unittest.TestCase):
    def draft(self, question, ctx=None, now=NOW):
        return draft_reminder(question, ctx or context(), utc_now(now))

    def test_once_tomorrow_uses_local_calendar_and_server_clock(self):
        r = self.draft("Напомни завтра в 9 утра продолжить курс")
        self.assertEqual(r["status"], "ACTION_PROPOSAL")
        self.assertEqual(r["reminderProposal"]["schedule"], {"type": "ONCE", "at": "2026-11-16T06:00:00Z", "timeZone": "Europe/Moscow"})
        r = self.draft("Напомни завтра в 9 утра", now="2026-11-15T22:30:00Z")
        self.assertEqual(r["reminderProposal"]["schedule"]["at"], "2026-11-17T06:00:00Z")

    def test_user_zone_overrides_profile_without_changing_profile(self):
        ctx = context("UTC"); original = copy.deepcopy(ctx)
        r = self.draft("Напомни завтра в 9 по Москве", ctx)
        self.assertEqual(r["reminderProposal"]["schedule"]["timeZone"], "Europe/Moscow")
        self.assertEqual(ctx, original)

    def test_weekday_once_does_not_become_weekly_because_of_po_moskve(self):
        r = self.draft("Напомни в понедельник в 9 по Москве", context(None))
        self.assertEqual(r["reminderProposal"]["schedule"]["type"], "ONCE")
        self.assertEqual(r["reminderProposal"]["schedule"]["at"], "2026-11-16T06:00:00Z")
        self.assertEqual(self.draft("Напомни в 9 прочитать журнал Понедельник")["status"], "CLARIFY")

    def test_unknown_zone_date_time_and_tax_deadline_are_clarified(self):
        for question, ctx in [
            ("Напомни завтра в 9", context(None)), ("Напомни завтра", context()),
            ("Напомни в 9 уплатить налог по УСН", context()),
            ("Напомни 30 февраля 2027 в 9", context()),
            ("Напомни сегодня в 9", context()), ("Напомни завтра в 25:30", context()),
            ("Напомни завтра в 9 или 10", context()),
            ("Напомни по будням в 9", context()),
        ]:
            with self.subTest(question=question):
                r = self.draft(question, ctx)
                self.assertEqual(r["status"], "CLARIFY")
                self.assertEqual(r["answer"].count("?"), 1)
                self.assertIsNone(r["reminderProposal"])

    def test_daily_weekly_monthly_and_original_31(self):
        cases = [("Напоминай ежедневно в 09:15 учиться", "DAILY"),
                 ("Напоминай каждый понедельник в 18:00", "WEEKLY"),
                 ("Напоминай ежемесячно 31-го числа в 09:00", "MONTHLY"),
                 ("Напоминай в последний день месяца в 09:00", "MONTHLY")]
        for question, kind in cases:
            with self.subTest(question=question):
                r = self.draft(question, now="2027-02-10T10:00:00Z")
                self.assertEqual(r["status"], "ACTION_PROPOSAL", r)
                schedule = r["reminderProposal"]["schedule"]
                self.assertEqual(schedule["type"], kind)
                validate_schedule(schedule, utc_now(NOW))
                if kind == "MONTHLY": self.assertEqual(schedule["dayOfMonth"], 31)
                if kind == "WEEKLY": self.assertEqual(schedule["dayOfWeek"], "MONDAY")

    def test_weekly_missing_day_and_monthly_missing_number(self):
        for question in ["Напоминай еженедельно в 9", "Напоминай ежемесячно в 9", "Напоминай каждый понедельник и вторник в 9", "Напоминай ежегодно 15 ноября в 9", "Напоминай еженедельно в 9 проверить средний чек"]:
            self.assertEqual(self.draft(question)["status"], "CLARIFY")

    def test_evening_prefix_and_compound_interval(self):
        r = self.draft("Напомни завтра вечером в 9 продолжить курс")
        self.assertEqual(r["reminderProposal"]["schedule"]["at"], "2026-11-16T18:00:00Z")
        r = self.draft("Напомни завтра в 09:30 вечера продолжить курс")
        self.assertEqual(r["reminderProposal"]["schedule"]["at"], "2026-11-16T18:30:00Z")
        r = self.draft("Напомни через 1 час 30 минут продолжить курс")
        self.assertEqual(r["reminderProposal"]["schedule"]["at"], "2026-11-15T11:30:00Z")

    def test_relative_interval_preserves_instant(self):
        r = self.draft("Напомни через 30 минут продолжить курс")
        self.assertEqual(r["reminderProposal"]["schedule"]["at"], "2026-11-15T10:30:00Z")

    def test_clarification_continuation_uses_previous_request(self):
        ctx = context();ctx["history"] = [{"role": "USER", "text": "Напомни завтра продолжить курс"},
                                           {"role": "ASSISTANT", "text": "Во сколько напомнить?"}]
        r = self.draft("В 9 утра", ctx)
        self.assertEqual(r["status"], "ACTION_PROPOSAL")
        self.assertEqual(r["reminderProposal"]["schedule"]["at"], "2026-11-16T06:00:00Z")

    def test_several_clarification_turns_keep_the_original_request(self):
        ctx = context(None);ctx["history"] = [
            {"role": "USER", "text": "Напомни завтра продолжить курс"},
            {"role": "ASSISTANT", "text": "В каком часовом поясе напомнить?"},
            {"role": "USER", "text": "По Москве"},
            {"role": "ASSISTANT", "text": "Во сколько напомнить?"}]
        r = self.draft("В 9 утра", ctx)
        self.assertEqual(r["status"], "ACTION_PROPOSAL")
        self.assertEqual(r["reminderProposal"]["schedule"]["at"], "2026-11-16T06:00:00Z")

    def test_new_topic_or_new_reminder_does_not_reuse_old_title(self):
        from reminder_proposals import pending_message
        ctx = context();ctx["history"] = [{"role": "USER", "text": "Напомни завтра про курс А"},
                                          {"role": "ASSISTANT", "text": "Во сколько напомнить?"}]
        self.assertIsNone(pending_message("Напомни, что такое CRM?", ctx))
        self.assertIsNone(pending_message("У меня пекарня", ctx))
        r = self.draft("Напомни завтра в 9 про курс Б", ctx)
        self.assertIn("курс Б", r["reminderProposal"]["title"])
        self.assertNotIn("курс А", r["reminderProposal"]["title"])

    def test_dst_gap_and_ambiguous_time(self):
        ctx = context("America/New_York")
        spring = self.draft("Напомни 2027-03-14 в 02:30", ctx, now="2027-03-01T00:00:00Z")
        self.assertEqual(spring["reminderProposal"]["schedule"]["at"], "2027-03-14T07:30:00Z")
        autumn = self.draft("Напомни 2027-11-07 в 01:30", ctx, now="2027-11-01T00:00:00Z")
        self.assertEqual(autumn["reminderProposal"]["schedule"]["at"], "2027-11-07T05:30:00Z")

    def test_step_links_are_owned_and_material_matches_step(self):
        ctx = context();ctx["currentPlan"] = plan_context()
        r = self.draft("Напомни завтра в 9 о втором шаге", ctx)
        self.assertEqual(r["reminderProposal"]["planStepId"], ctx["currentPlan"]["steps"][1]["id"])
        self.assertIsNone(r["reminderProposal"]["materialId"])
        self.assertEqual(self.draft("Напомни завтра в 9 о шестом шаге", ctx)["status"], "CLARIFY")
        self.assertEqual(self.draft("Напомни завтра в 9 о шаге 9", ctx)["status"], "CLARIFY")
        self.assertEqual(self.draft("Напомни завтра в 9 про 00000000-0000-4000-8000-000000000999", ctx)["status"], "CLARIFY")


class ProfileTests(unittest.TestCase):
    def test_explicit_values_and_no_derived_defaults(self):
        ctx = context()
        self.assertTrue(supported_change("industry", "кофейня", "кофейня", "У меня кофейня", ctx))
        self.assertTrue(supported_change("industry", "кофейня", "кофейню", "Смени сферу на кофейню", ctx))
        self.assertTrue(supported_change("industry", "кофейня", "кофейню", "Планирую открыть кофейню", ctx))
        self.assertFalse(supported_change("industry", "кофейня", "кофейню", "Смени сферу, только не на кофейню", ctx))
        self.assertFalse(supported_change("industry", "кофейня", "кофейни", "У меня нет кофейни", ctx))
        self.assertFalse(supported_change("businessStage", "OPERATING", "У меня кофейня", "У меня кофейня", ctx))
        self.assertTrue(supported_change("experience", "BEGINNER", "Я новичок", "Я новичок", ctx))
        self.assertTrue(supported_change("taxRegime", "USN_6", "УСН 6%", "Я на УСН 6%", ctx))
        self.assertFalse(supported_change("taxRegime", "USN_6", "УСН 6%", "Стоит ли выбрать УСН 6%?", ctx))

    def test_hypothetical_other_people_negation_and_old_information(self):
        ctx = context()
        for message in ["Если у меня кофейня, что делать?", "Мой друг открыл кофейню", "У меня не кофейня"]:
            value = "кофейня" if "кофейня" in message else "кофейню"
            self.assertFalse(supported_change("industry", value, value, message, ctx))
        message = "Раньше у меня кофейня, теперь у меня пекарня"
        self.assertFalse(supported_change("industry", "кофейня", "кофейня", message, ctx))
        self.assertTrue(supported_change("industry", "пекарня", "пекарня", message, ctx))
        self.assertFalse(supported_change("taxRegime", "NPD", "не самозанятый", "Я не самозанятый", ctx))

    def test_null_only_when_requested_and_zone_not_inferred_from_city(self):
        ctx = context()
        self.assertFalse(supported_change("industry", None, "кофейня", "У меня кофейня", ctx))
        self.assertTrue(supported_change("industry", None, "Удали сферу из профиля", "Удали сферу из профиля", ctx))
        self.assertFalse(supported_change("industry", None, "Не удали сферу из профиля", "Не удали сферу из профиля", ctx))
        self.assertFalse(supported_change("timeZone", "Europe/Moscow", "Москве", "Я живу в Москве", ctx))
        self.assertTrue(supported_change("timeZone", "Europe/Moscow", "Мой часовой пояс Europe/Moscow", "Мой часовой пояс Europe/Moscow", ctx))

    def test_extractor_drops_invented_or_historical_fields(self):
        client = LocalModelStub()
        client.responses = [chat_response({"changes": [
            {"field": "industry", "value": "кофейня", "evidence": "У меня кофейня"},
            {"field": "businessStage", "value": "OPERATING", "evidence": "У меня кофейня"},
            {"field": "taxRegime", "value": "USN_6", "evidence": "я на УСН 6%"}]})]
        patch, _, _ = extract_profile_patch("У меня кофейня", context(), client, runtime.CHAT_MODEL)
        self.assertEqual(patch, {"industry": "кофейня"})


class FullContractTests(unittest.TestCase):
    def test_schema_contains_full_contract_variants(self):
        schema = runtime.response_schema([], [{"id": MID}], {"currentPlan": plan_context()})
        self.assertIn("ACTION_PROPOSAL", schema["properties"]["status"]["enum"])
        self.assertIn("industry", schema["properties"]["profilePatch"]["properties"])
        self.assertEqual(len(schema["properties"]["reminderProposal"]["anyOf"][1]["properties"]["schedule"]["anyOf"]), 4)

    def test_profile_patch_can_accompany_a_grounded_consultation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); write_dataset(path);client = LocalModelStub();runtime.index_dataset(path, client)
            from test_rag_runtime import short_answer
            client.responses = [chat_response({"changes": [{"field": "experience", "value": "BEGINNER", "evidence": "Я новичок"}]}), chat_response(short_answer())]
            ctx = context();original = copy.deepcopy(ctx)
            result = runtime.ask(runtime.Retriever(path, client), "Я новичок. Как формулировать задачу и оценивать доступные ресурсы?", client,
                                 context=ctx, now=NOW, answer_mode="generative")
            self.assertEqual(result["response"]["profilePatch"], {"experience": "BEGINNER"})
            self.assertEqual(result["response"]["status"], "ANSWER")
            self.assertEqual(ctx, original)

    def test_no_second_format_repair_after_profile_repair(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp);data = fixture()
            data["ArticleEmbedItems"][0]["HtmlContent"] = ("<p>Исследуйте потребности клиентов и сформулируйте задачу.</p>"
                "<p>Соберите данные о покупках и предпочтениях клиентов.</p>"
                "<p>Проверьте результат небольшого эксперимента и сравните продажи.</p>")*10
            write_dataset(path, data); client = LocalModelStub(); runtime.index_dataset(path, client)
            client.responses = [chat_response({"wrong": True}),
                                chat_response({"changes": [{"field": "experience", "value": "BEGINNER", "evidence": "Я новичок"}]}),
                                chat_response({"wrong": True})]
            client.deadline = 42
            with self.assertRaises(runtime.RagError):
                runtime.ask(runtime.Retriever(path, client), "Я новичок. Составь план исследования клиентов", client, now=NOW)
            self.assertEqual(len([r for r in client.requests if r[0] == "/api/chat"]), 3)
            self.assertEqual(client.deadline, 42)

    def test_current_plan_validation_and_immutability(self):
        plan = plan_context(); original = copy.deepcopy(plan)
        validate_current_plan(plan)
        loaded = runtime.load_context({"currentPlan": plan})
        self.assertEqual(loaded["currentPlan"], original)
        plan["steps"][1]["id"] = plan["steps"][0]["id"]
        with self.assertRaises(runtime.RagError): runtime.load_context({"currentPlan": plan})

    def test_completed_plan_does_not_restart_done_steps(self):
        ctx = context();ctx["currentPlan"] = plan_context()
        for step in ctx["currentPlan"]["steps"]: step["status"] = "DONE"
        client = LocalModelStub()
        result = runtime.ask(None, "Что делать дальше?", client, context=ctx, now=NOW)
        self.assertEqual(result["response"]["status"], "CLARIFY")
        self.assertEqual(result["diagnostics"]["route"], "plan_completed")
        self.assertEqual(client.requests, [])

    def test_complete_action_validates_but_unknown_step_past_date_and_false_completion_fail(self):
        ctx = context();ctx["currentPlan"] = plan_context()
        action = draft_reminder("Напомни завтра в 9 о втором шаге", ctx, utc_now(NOW))
        result = {"schemaVersion": rag.SCHEMA, **action, "sourceChunkIds": [], "recommendations": [], "profilePatch": {}, "planProposal": None}
        runtime.validate_response(result, [], [], ctx, NOW)
        for mutate in ["past", "step", "claim", "both"]:
            bad = copy.deepcopy(result)
            if mutate == "past": bad["reminderProposal"]["schedule"]["at"] = NOW
            if mutate == "step": bad["reminderProposal"]["planStepId"] = "00000000-0000-4000-8000-000000000999"
            if mutate == "claim": bad["answer"] = "Напоминание создано"
            if mutate == "both": bad["planProposal"] = {"goal": "Цель", "steps": []}
            with self.subTest(mutate=mutate), self.assertRaises(runtime.RagError):
                runtime.validate_response(bad, [], [], ctx, NOW)

    def test_runtime_reminder_needs_no_model_inference_and_keeps_deadline(self):
        client = LocalModelStub()
        result = runtime.ask(None, "Напомни завтра в 9 утра продолжить курс", client, context=context(), now=NOW)
        self.assertEqual(result["response"]["status"], "ACTION_PROPOSAL")
        self.assertEqual(client.requests, [])
        self.assertIsNone(client.deadline)

    def test_plan_generation_matches_schema_and_only_uses_known_materials(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); data = fixture()
            # Enough distinct excerpts; all content is synthetic.
            data["ArticleEmbedItems"][0]["HtmlContent"] = ("<p>Исследуйте потребности покупателей и формулируйте проверяемую задачу.</p>"
                "<p>Соберите контакты покупателей и согласуйте способ получения обратной связи.</p>"
                "<p>Проверьте результат небольшого эксперимента и сравните показатели продаж.</p>")*10
            _, chunks = write_dataset(path, data);client = LocalModelStub(); runtime.index_dataset(path, client)
            client.responses = [chat_response({"status": "ANSWER", "clarification": "", "steps": [
                {"title": title, "evidenceId": f"E{i}"}
                for i, title in enumerate(["Исследовать потребности", "Определить задачу", "Проверить результат"], 1)]})]
            result = runtime.ask(runtime.Retriever(path, client), "Составь план исследования покупателей", client, now=NOW)
            response = result["response"]
            self.assertEqual(response["status"], "ANSWER")
            self.assertEqual(len(response["planProposal"]["steps"]), 3)
            self.assertTrue(response["sourceChunkIds"])
            for step in response["planProposal"]["steps"]:
                UUID(step["materialIds"][0])
            self.assertIsNone(response["reminderProposal"])

    def test_missing_goal_and_mixed_plan_reminder_are_clarified(self):
        client = LocalModelStub()
        for message in ["Составь план", "Составь план и напомни завтра в 9"]:
            result = runtime.ask(None, message, client, now=NOW)
            self.assertEqual(result["response"]["status"], "CLARIFY")
            self.assertIsNone(result["response"]["planProposal"])
            self.assertIsNone(result["response"]["reminderProposal"])


if __name__ == "__main__":
    unittest.main()

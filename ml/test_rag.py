"""Проверки на синтетических данных: реальные тексты банка сюда не добавлять."""

import copy
import json
from pathlib import Path
import tempfile
import unittest

import rag


NOW = "2026-11-12T13:05:26Z"
PUBLISHED = "00000000-0000-4000-8000-000000000001"
OPEN = "00000000-0000-4000-8000-000000000002"
PAID = "00000000-0000-4000-8000-000000000003"
ARTICLE = "00000000-0000-4000-8000-000000000004"
COURSE = "00000000-0000-4000-8000-000000000005"
LESSON = "00000000-0000-4000-8000-000000000006"


def fixture():
    data = {key: [] for key in rag.TABLES}
    data["Statuses"] = [{"Id": PUBLISHED, "Title": "Опубликовано"}]
    data["CourseTypes"] = [{"Id": OPEN, "Title": "Открытый", "IsDeleted": False},
                           {"Id": PAID, "Title": "Платный", "IsDeleted": False}]
    data["Articles"] = [{"Id": ARTICLE, "Title": "Синтетическая статья", "Description": None,
                         "StatusId": PUBLISHED, "IsDeleted": False, "IsPublic": True,
                         "IsClosed": False, "Url": "https://kurs.alfabank.ru/articles/synthetic/"}]
    data["ArticleEmbedItems"] = [
        {"Id": "b", "ArticleId": ARTICLE, "Order": 2, "Type": 0,
         "HtmlContent": "<p>Вторая часть: измеряем результат действий и уточняем дальнейший план.</p>", "Params": "{}"},
        {"Id": "a", "ArticleId": ARTICLE, "Order": 1, "Type": 0,
         "HtmlContent": "<h2>Первая часть</h2><p>Начинаем с формулировки задачи и доступных ресурсов.</p>", "Params": "{}"},
    ]
    return data


def add_course(data, *, cost=None, course_type=OPEN):
    data["Courses"].append({"Id": COURSE, "Title": "Учебный курс", "Description": "Разрешённое краткое описание.",
                            "StatusId": PUBLISHED, "IsDeleted": False, "IsClosed": False,
                            "Url": "https://kurs.alfabank.ru/courses/synthetic/", "Cost": cost, "TypeId": course_type})
    data["Lessons"].append({"Id": LESSON, "Title": "Урок", "IsDeleted": False, "IsGroup": False})
    data["CourseLessonBindings"].append({"Id": "binding", "CourseId": COURSE, "LessonId": LESSON,
                                        "IsDeleted": False, "IsClosed": False, "ParentGroupId": None, "ClosedUntil": None})
    data["LessonEmbedItems"].append({"Id": "block", "LessonId": LESSON, "Order": 1, "Type": 0,
                                    "HtmlContent": "<p>Содержательный текст учебного урока, который требуется защитить при платном доступе.</p>", "Params": "{}"})


class PreparationTests(unittest.TestCase):
    def test_html_removes_scripts_keeps_table_and_lists(self):
        result = rag.clean_html("<h2>Заголовок</h2><script>secret()</script><style>bad</style>"
                                "<ul><li>Пункт один</li><li>Пункт два</li></ul>"
                                "<table><tr><td>Товар</td><td>10</td></tr></table>")
        self.assertNotIn("secret", result)
        self.assertNotIn("bad", result)
        self.assertIn("- Пункт один", result)
        self.assertIn("Товар | 10", result)
        self.assertEqual(rag.clean_html("\ufeff\u200bСло\u00adво\u2060"), "Слово")

    def test_params_extracts_lists_but_not_advertisements(self):
        self.assertIn("Полезный пункт", rag.block_text({"Type": 4, "Params": '{"items":["Полезный пункт"]}'}))
        self.assertEqual(rag.block_text({"Type": 8, "Params": '{"item":{"description":"Рекламный текст"}}'}), "")
        self.assertEqual(rag.block_text({"Type": 5, "Params": '{"video":{"title":"Нет транскрипта"}}'}), "")

    def test_order_is_preserved(self):
        _, chunks, _ = rag.build(fixture(), NOW)
        self.assertLess(chunks[0]["text"].index("Первая часть"), chunks[0]["text"].index("Вторая часть"))

    def test_unknown_and_closed_articles_are_excluded(self):
        for field, value in [("IsPublic", False), ("IsClosed", True), ("StatusId", "unknown"), ("IsDeleted", None)]:
            data = fixture()
            data["Articles"][0][field] = value
            materials, chunks, _ = rag.build(data, NOW)
            self.assertEqual((materials, chunks), ([], []))

    def test_slug_requires_observed_typed_link(self):
        row = {"Url": "synthetic"}
        registry = rag.link_registry({"Articles": [{"Description": '<a href="https://kurs.alfabank.ru/articles/synthetic/">Источник</a>'}]})
        self.assertEqual(rag.resolve_url(row, "ARTICLE", registry), "https://kurs.alfabank.ru/articles/synthetic/")
        self.assertIsNone(rag.resolve_url(row, "COURSE", registry))
        self.assertIsNone(rag.resolve_url({"Url": "unknown"}, "ARTICLE", registry))
        self.assertIsNone(rag.canonical_url("https://kurs.alfabank.ru.evil.test/articles/synthetic/"))

    def test_paid_course_is_catalog_only_and_lesson_excluded(self):
        data = fixture()
        add_course(data, cost=100, course_type=PAID)
        materials, chunks, _ = rag.build(data, NOW)
        course = next(m for m in materials if m["sourceType"] == "COURSE")
        self.assertTrue(course["isPaid"])
        self.assertFalse(course["answerAllowed"])
        self.assertFalse(any(m["sourceType"] == "LESSON" for m in materials))
        self.assertFalse(any("защитить" in c["text"] for c in chunks))

    def test_subscription_never_becomes_free_due_to_null_cost(self):
        data = fixture()
        add_course(data)
        data["CourseSubscriptions"] = [{"CourseId": COURSE}]
        materials, _, _ = rag.build(data, NOW)
        self.assertEqual({m["sourceType"] for m in materials}, {"ARTICLE"})

    def test_free_lesson_uses_real_parent_url_and_external_denied(self):
        data = fixture()
        add_course(data)
        materials, chunks, _ = rag.build(data, NOW)
        lesson = next(m for m in materials if m["sourceType"] == "LESSON")
        self.assertEqual(lesson["url"], data["Courses"][0]["Url"])
        self.assertEqual(lesson["parentMaterialIds"], [rag.material_id("COURSE", COURSE)])
        self.assertTrue(lesson["answerAllowed"])
        self.assertTrue(all(c["externalTextAllowed"] is False for c in chunks))

    def test_closed_ancestor_group_protects_lesson(self):
        data = fixture()
        add_course(data)
        group_id = "00000000-0000-4000-8000-000000000007"
        data["Lessons"].append({"Id": group_id, "Title": "Группа", "IsDeleted": False, "IsGroup": True})
        data["CourseLessonBindings"][0]["ParentGroupId"] = group_id
        data["CourseLessonBindings"].append({"Id": "group-binding", "CourseId": COURSE, "LessonId": group_id,
                                            "IsDeleted": False, "IsClosed": True, "ParentGroupId": None})
        materials, chunks, _ = rag.build(data, NOW)
        self.assertFalse(any(m["sourceType"] == "LESSON" for m in materials))
        self.assertFalse(any("защитить" in c["text"] for c in chunks))

    def test_conflicting_second_parent_protects_shared_lesson(self):
        data = fixture()
        add_course(data)
        data["CourseLessonBindings"].append({"Id": "unknown-binding", "CourseId": "unknown", "LessonId": LESSON,
                                            "IsDeleted": False, "IsClosed": False, "ParentGroupId": None})
        materials, _, _ = rag.build(data, NOW)
        self.assertFalse(any(m["sourceType"] == "LESSON" for m in materials))

    def test_long_text_stays_bounded_and_tail_is_not_lost(self):
        text = "Большой раздел " * 1500 + "\n\nКороткий хвост."
        chunks = rag.split_text(text)
        self.assertTrue(all(30 <= len(c) <= 6000 for c in chunks))
        self.assertIn("Короткий хвост.", chunks[-1])
        self.assertEqual(" ".join(" ".join(chunks).split()), " ".join(text.split()))

    def test_postgres_fractional_seconds_and_future_publication(self):
        self.assertEqual(rag.utc("2026-07-06T13:41:32.66925+00:00"), "2026-07-06T13:41:32.669250Z")
        self.assertTrue(rag.after("2026-11-12T13:05:26.1Z", NOW))
        data = fixture()
        data["Articles"][0]["Published"] = "2026-11-13T00:00:00Z"
        self.assertEqual(rag.build(data, NOW)[0], [])

    def test_validator_rejects_tampering_even_after_file_hash_update(self):
        materials, chunks, _ = rag.build(fixture(), NOW)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            rag.write_jsonl(path / "materials.jsonl", materials)
            rag.write_jsonl(path / "chunks.jsonl", chunks)
            manifest = {"schemaVersion": rag.SCHEMA, "datasetVersion": "synthetic", "createdAt": NOW,
                        "sourceSnapshot": "sha256:" + "0" * 64,
                        "materialsCount": len(materials), "chunksCount": len(chunks),
                        "materialsSha256": rag.sha256((path / "materials.jsonl").read_bytes()),
                        "chunksSha256": rag.sha256((path / "chunks.jsonl").read_bytes())}
            rag.dump_json(path / "manifest.json", manifest)
            self.assertTrue(rag.validate(path)["valid"])
            for key, value in [("text", "Подменённое содержимое достаточно длинного фрагмента."),
                               ("externalTextAllowed", True), ("materialId", "unknown")]:
                broken = copy.deepcopy(chunks)
                broken[0][key] = value
                rag.write_jsonl(path / "chunks.jsonl", broken)
                manifest["chunksSha256"] = rag.sha256((path / "chunks.jsonl").read_bytes())
                rag.dump_json(path / "manifest.json", manifest)
                with self.assertRaises(ValueError):
                    rag.validate(path)


if __name__ == "__main__":
    unittest.main()

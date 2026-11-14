"""Синтетические проверки типов, схемы и отсутствия исполнения SQL."""

from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import import_backup
import rag


class BackupTests(unittest.TestCase):
    def test_types_and_copy_column_order(self):
        entries = [
            SimpleNamespace(namespace="public", desc="TABLE", tag="Courses", defn='''
CREATE TABLE public."Courses" (
    "Id" uuid NOT NULL,
    "Cost" integer,
    "IsClosed" boolean DEFAULT false NOT NULL,
    "Published" timestamp with time zone
);'''),
            SimpleNamespace(namespace="public", desc="TABLE DATA", tag="Courses",
                            copy_stmt='COPY public."Courses" ("Cost", "Published", "Id", "IsClosed") FROM stdin;'),
        ]
        dump = SimpleNamespace(entries=entries, table_data=lambda *_: iter([
            (None, "2026-11-13 10:00:00+00", "a", "f"), ("100", None, "b", "t")]))
        with patch.dict(rag.TABLES, {"Courses": "Id Cost IsClosed Published"}, clear=True):
            rows = sorted(import_backup.read_snapshot(dump)["Courses"], key=lambda row: row["Id"])
            self.assertEqual(rows[0], {"Id": "a", "Cost": None, "IsClosed": False,
                                       "Published": "2026-11-13T10:00:00Z"})
            self.assertEqual(rows[1]["Cost"], 100)
            self.assertIs(rows[1]["IsClosed"], True)
            entries[1].copy_stmt = 'COPY public."Courses" ("Id") FROM stdin;'
            with self.assertRaisesRegex(ValueError, "Схема"):
                import_backup.read_snapshot(dump)

    def test_invalid_boolean_is_not_treated_as_free_or_open(self):
        with self.assertRaises(ValueError):
            import_backup.convert_value("false", "boolean")

    def test_archive_does_not_extract_member_paths(self):
        import pgdumplib
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "data/source").mkdir(parents=True)
            archive = root / "input.zip"
            with zipfile.ZipFile(archive, "w") as stream:
                stream.writestr("../../escape.backup", b"PGDMP synthetic")
                stream.writestr("../../unexpected.txt", b"do not extract")
            output = root / "data/source/snapshot.json"
            with patch.object(rag, "ROOT", root), patch.object(pgdumplib, "load") as load:
                with patch.object(import_backup, "read_snapshot", return_value={"Courses": []}):
                    import_backup.import_backup(archive, output)
                self.assertTrue(output.exists())
                self.assertEqual(Path(load.call_args.args[0]).name, "source.backup")
                self.assertFalse((root / "unexpected.txt").exists())
                with self.assertRaisesRegex(ValueError, "уже существует"):
                    import_backup.import_backup(archive, output)
                with self.assertRaisesRegex(ValueError, "только внутри"):
                    import_backup.import_backup(archive, root / "outside.json")


if __name__ == "__main__":
    unittest.main()

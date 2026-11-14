from decimal import Decimal
from pathlib import Path
import random
import unittest

from calculation import compute_breakeven, formula_source, parse_breakeven


class CalculationTests(unittest.TestCase):
    def test_explicit_inputs_produce_250_not_the_example_from_the_source(self):
        parsed = parse_breakeven("Постоянные расходы 100000 рублей, цена товара 1000 рублей, переменные расходы 600 рублей на штуку. Сколько продать для выхода в ноль?")
        self.assertEqual(parsed["status"], "CALCULATE")
        self.assertEqual(compute_breakeven(parsed)["units"], 250)

    def test_fractional_units_are_rounded_up(self):
        parsed = parse_breakeven("Рассчитай точку безубыточности: постоянные затраты 80 000, цена 750, переменные расходы 452 на единицу.")
        self.assertEqual(compute_breakeven(parsed)["units"], 269)

    def test_decimal_and_multiplier_parsing(self):
        parsed = parse_breakeven("Точка безубыточности: постоянные расходы 2,5 млн рублей, цена товара 1000,50 рублей, переменные расходы 400,50 рублей на штуку.")
        self.assertEqual(parsed["fixed"], Decimal("2500000"))
        self.assertEqual(parsed["price"], Decimal("1000.50"))
        self.assertEqual(compute_breakeven(parsed)["units"], 4167)

    def test_missing_or_ambiguous_inputs_require_clarification(self):
        questions = [
            "Рассчитай точку безубыточности: постоянные расходы 100000, цена 1000.",
            "Точка безубыточности: постоянные расходы 100000, цена 1000, цена 1200, переменные расходы 600 на штуку.",
            "Точка безубыточности: постоянные расходы 100000, цена 1000, переменные расходы 600 за весь месяц.",
            "Точка безубыточности: постоянные расходы 100,000, цена 1000, переменные расходы 600 на штуку.",
            "Точка безубыточности: постоянные расходы 100000 рублей, цена 1000 долларов, переменные расходы 600 рублей на штуку.",
            "Точка безубыточности: постоянные расходы -100000, цена 1000, переменные расходы 600 на штуку.",
            "Точка безубыточности: постоянные расходы 100000, цена 0, переменные расходы 600 на штуку.",
            "Точка безубыточности: постоянные расходы 1e9, цена 1000, переменные расходы 600 на штуку.",
        ]
        for question in questions:
            with self.subTest(question=question):
                self.assertEqual(parse_breakeven(question)["status"], "CLARIFY")

    def test_zero_and_negative_contribution_have_no_finite_break_even(self):
        for variable in [Decimal(1000), Decimal(1200)]:
            self.assertIsNone(compute_breakeven({"fixed": Decimal(100000), "price": Decimal(1000), "variable": variable})["units"])
        self.assertEqual(compute_breakeven({"fixed": Decimal(0), "price": Decimal(1000), "variable": Decimal(600)})["units"], 0)

    def test_randomized_minimal_quantity_property(self):
        rng = random.Random(42)
        for _ in range(100):
            fixed = Decimal(rng.randrange(0, 10000000))/100
            variable = Decimal(rng.randrange(0, 100000))/100
            price = variable+Decimal(rng.randrange(1, 10000))/100
            result = compute_breakeven({"fixed": fixed, "price": price, "variable": variable})
            n = result["units"]; margin = price-variable
            self.assertGreaterEqual(n*margin, fixed)
            if n:
                self.assertLess((n-1)*margin, fixed)

    def test_informational_questions_are_not_calculations(self):
        for question in ["Как рассчитать точку безубыточности?", "Что такое маржа?", "Как составить бизнес-план?"]:
            self.assertIsNone(parse_breakeven(question))

    def test_money_break_even_formula_is_not_used_as_a_unit_formula(self):
        self.assertIsNone(formula_source([{"text": "Точка безубыточности = постоянные затраты / маржинальность."}]))
        self.assertIsNotNone(formula_source([{"text": "Точка безубыточности = Постоянные расходы : Маржа с одной единицы товара."}]))


if __name__ == "__main__":
    unittest.main()

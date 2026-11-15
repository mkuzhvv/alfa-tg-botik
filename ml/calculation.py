"""Детерминированный расчёт одной товарной позиции; никаких eval/LLM-чисел."""
from decimal import Decimal, InvalidOperation, ROUND_CEILING
import re

from lexical import normalize_text


NUMBER = r"([+\-]?\d(?:[\d \u00a0\u202f]*\d)?(?:[.,]\d+)?)(?:\s*(тыс\.?|тысяч\w*|млн\.?|миллион\w*))?(?=\s|руб|₽|[,;.!?]|$)"
SEPARATOR = r"\s*(?:(?:составляют|составляет|равны|равна|равен)|[=:—])?\s*"
LABELS = {"fixed": r"постоянн\w*\s+(?:расход\w*|затрат\w*)",
          "price": r"цена(?:\s+(?:продажи|товара|единицы))?",
          "variable": r"переменн\w*\s+(?:расход\w*|затрат\w*)"}


def clarification(message):
    return {"status": "CLARIFY", "answer": message, "route": "calculation_clarify"}


def parse_breakeven(question):
    text = normalize_text(question).replace("−", "-")
    intent = re.search(r"безубыточ|выйти в ноль|выхода в ноль", text)
    if not intent or not re.search(r"\d", text):
        return None
    if re.search(r"\busd\b|\beur\b|доллар|евро|[$€£]", text):
        return clarification("Для этого расчёта нужны сопоставимые суммы. Можете привести постоянные расходы, цену и переменные расходы на единицу в одной валюте?")
    values = {}
    matches = {}
    for key, label in LABELS.items():
        found = list(re.finditer(label+SEPARATOR+NUMBER, text))
        if len(found) != 1:
            return clarification("Каковы постоянные расходы за период, цена одной единицы товара и переменные расходы на одну единицу?")
        match = found[0]
        raw, multiplier = match.group(1), match.group(2)
        raw = re.sub(r"\s", "", raw).replace(",", ".")
        if len(raw) > 24 or re.search(r"\.\d{3,}$", raw):
            return clarification("В числах неоднозначные разделители или слишком много знаков. Можете записать суммы без разделителей тысяч и с двумя знаками после запятой?")
        try:
            value = Decimal(raw)
        except InvalidOperation:
            return clarification("Не удалось однозначно прочитать суммы. Можете повторить три значения числами?")
        if multiplier:
            value *= Decimal(1000000 if multiplier.startswith(("млн", "миллион")) else 1000)
        if abs(value) > Decimal("1e15"):
            return clarification("Сумма выходит за диапазон этого калькулятора. Можете проверить единицы измерения и величины расходов и цены?")
        values[key] = value
        matches[key] = match
    tail = text[matches["variable"].end():matches["variable"].end()+65]
    if not re.search(r"(?:на|за)\s+(?:одну\s+)?(?:штук\w*|единиц\w*|шт\b)", tail):
        return clarification("Указанные переменные расходы относятся к одной единице товара или ко всему объёму за период?")
    if values["fixed"] < 0 or values["variable"] < 0 or values["price"] <= 0:
        return clarification("Для этой модели цена должна быть положительной, расходы — неотрицательными. Можете проверить исходные значения?")
    return {"status": "CALCULATE", **values}


def compute_breakeven(values):
    fixed, price, variable = (values[k] for k in ["fixed", "price", "variable"])
    contribution = price-variable
    if fixed == 0:
        units = 0
    elif contribution <= 0:
        units = None
    else:
        units = int((fixed/contribution).to_integral_value(rounding=ROUND_CEILING))
    return {"fixed": fixed, "price": price, "variable": variable, "contribution": contribution, "units": units}


def display(value):
    text = format(value, "f")
    return (text.rstrip("0").rstrip(".") if "." in text else text).replace(".", ",")


def formula_source(hits):
    for hit in hits:
        for line in hit["text"].splitlines():
            if (re.search(r"точка\s+безубыточности[^\n]*=[^\n]*постоянн", line, re.I)
                    and re.search(r"единиц|\bшт\b|цена.*переменн", line, re.I)):
                return hit, line.strip()
    return None


def calculation_result(values, source, retriever, elapsed):
    import rag
    result = compute_breakeven(values)
    hit, formula = source
    material = retriever.materials[hit["materialId"]]
    if result["units"] is None:
        answer = (f"Маржинальный доход на единицу: {display(result['price'])} − {display(result['variable'])} = {display(result['contribution'])}. "
                  "Цена не покрывает переменные расходы. При положительных постоянных расходах увеличением числа продаж выйти в ноль по этой модели нельзя.")
    else:
        calculation = f"{display(result['fixed'])} / {display(result['contribution'])}" if result["contribution"] > 0 else "при нулевых постоянных расходах"
        answer = (f"Маржинальный доход на единицу: {display(result['price'])} − {display(result['variable'])} = {display(result['contribution'])}. "
                  f"Точка безубыточности: {calculation} → {result['units']} ед. "
                  "Количество округлено вверх. Расчёт для одной товарной позиции за период, к которому относятся постоянные расходы.")
    return {"response": {"schemaVersion": rag.SCHEMA, "status": "ANSWER", "answer": answer,
                        "sourceChunkIds": [hit["chunkId"]], "recommendations": [], "profilePatch": {},
                        "planProposal": None, "reminderProposal": None},
            "references": [{"chunkId": hit["chunkId"], "materialId": hit["materialId"], "title": material["title"], "url": material["url"]}],
            "recommendationCards": [], "diagnostics": {"route": "calculation", "calculatorVersion": "breakeven-1",
                "elapsedSeconds": elapsed, "attempts": 0, "answerMode": "deterministic_calculation",
                "formula": formula, "inputsAndResult": {k: str(v) if isinstance(v, Decimal) else v for k, v in result.items()},
                "groundingCheck": "formula from source + Decimal arithmetic on explicit user inputs",
                "sources": [{"chunkId": hit["chunkId"], "materialId": hit["materialId"], "score": hit["score"]}],
                "sourceChunkIdsSent": [], "sourcesSent": 0, "catalogSent": 0}}

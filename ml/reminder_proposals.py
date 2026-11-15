"""Разбор явных русских расписаний. Расписание предлагается, но не исполняется."""
from datetime import date, datetime, time, timedelta, timezone
import re
from uuid import UUID

from feature_contract import DAYS, validate_schedule, zone
from lexical import normalize_text

WEEKDAYS = ["понедельник", "вторник", "сред", "четверг", "пятниц", "суббот", "воскресень"]
WEEKDAY_PATTERNS = [r"понедельник(?:а|у|е|ам|ами|ах)?", r"вторник(?:а|у|е|ам|ами|ах)?",
                    r"сред(?:а|у|ы|е|ам|ами|ах)", r"четверг(?:а|у|е|ам|ами|ах)?",
                    r"пятниц(?:а|у|ы|е|ам|ами|ах)", r"суббот(?:а|у|ы|е|ам|ами|ах)",
                    r"воскресень(?:е|я|ю|ем|ям|ями|ях)"]
WEEKDAY_LABELS = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]
DAY_PATTERN = "(?:"+"|".join(WEEKDAY_PATTERNS)+")"
MONTHS = ["январ", "феврал", "март", "апрел", "мая", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр"]


class NeedClarification(ValueError):
    pass


def requested(text):
    normalized = normalize_text(text)
    if re.match(r"\s*(как|зачем|почему|что)\b", normalized) or re.match(r"\s*напомни\s*(?:мне\s*)?[, ]*\s*(что такое|определение|чем отличается)", normalized):
        return False
    return bool(re.search(r"\bнапомни\b|\bнапоминай\b|\b(?:создай|поставь|добавь|предложи)\b.{0,35}напоминани", normalized))


def pending_layers(question, context):
    history = context["history"]
    normalized = normalize_text(question)
    if requested(question) or re.match(r"\s*напомни\s*(?:мне\s*)?[, ]*\s*(что такое|определение|чем отличается)", normalized):
        return []
    if re.match(r"\s*(составь|создай|измени|обнови|удали|отмени|очисти)\b", normalized):
        return []
    if re.search(r"у меня|я новичок", normalized) and not re.search(r"часов\w*\s+пояс", normalized):
        return []
    if not history or history[-1]["role"] != "ASSISTANT" or not re.search(r"дат|время|во сколько|пояс|числ|день недели|шаг", normalize_text(history[-1]["text"])):
        return []
    if re.match(r"\s*(как|почему|зачем|что такое)\b", normalize_text(question)):
        return []
    layers = []
    for row in reversed(history[:-1]):
        if row["role"] != "USER":
            continue
        value = row["text"]
        if requested(value):
            return layers+[value]
        if len(value) > 300 or re.match(r"\s*(как|почему|зачем|что такое|составь|создай|отмени|удали)\b", normalize_text(value)):
            return []
        layers.append(value)
    return []


def pending_message(question, context):
    layers = pending_layers(question, context)
    return layers[-1] if layers else None


def explicit_zone(text):
    found = set(re.findall(r"(?:Africa|America|Antarctica|Arctic|Asia|Atlantic|Australia|Europe|Indian|Pacific|Etc)/[A-Za-z_+\-0-9]+(?:/[A-Za-z_+\-0-9]+)?", text))
    lower = normalize_text(text)
    if re.search(r"\bне\s+(по\s+москве|московск|[a-z]+/[a-z_+/\-]+|utc\b)", lower):
        raise NeedClarification("Какой подтверждённый часовой пояс использовать вместо указанного?")
    if re.search(r"\bмск\s*[+-]|по\s+москве\s*[+-]", lower):
        raise NeedClarification("Укажите часовой пояс в формате IANA для однозначного смещения?")
    offset = re.search(r"\butc\s*([+-]\d{1,2})(?::(\d{2}))?", lower)
    if offset:
        if offset[2] and offset[2] != "00":
            raise NeedClarification("Укажите часовой пояс в формате IANA, например Asia/Kolkata?")
        hours = int(offset[1])
        found.add("UTC" if hours == 0 else "Etc/GMT" + ("-" if hours > 0 else "+") + str(abs(hours)))
    elif re.search(r"\butc\b", lower):
        found.add("UTC")
    if re.search(r"по москве|московск\w*\s+врем|часов\w*\s+пояс.{0,15}московск|\bмск\b|\bmsk\b", lower) or lower.strip(" .!?") in {"москва", "московское", "московское время"}:
        found.add("Europe/Moscow")
    if len(found) > 1:
        raise NeedClarification("Какой один часовой пояс использовать для этого напоминания?")
    if found:
        value = next(iter(found))
        try:
            zone(value)
        except ValueError:
            raise NeedClarification("Укажите существующий часовой пояс IANA, например Europe/Moscow?")
        return value
    return None


def clock(text):
    normalized = normalize_text(text)
    def converted(hour, minute, period, position):
        before = normalized[max(0, position-25):position]
        prefix = re.search(r"(вечером|утром|днем|ночью)\s*(?:в\s*)?$", before)
        if not period and prefix:
            period = {"вечером": "вечера", "утром": "утра", "днем": "дня", "ночью": "ночи"}[prefix[1]]
        if period:
            if hour == 12 and period in {"утра", "вечера"} or hour > 12 and period in {"утра", "ночи"}:
                raise NeedClarification("Укажите точное время в формате 24 часов HH:mm?")
            if 1 <= hour <= 12 and period in {"вечера", "дня"}:
                hour = hour % 12+12
            elif hour == 12 and period == "ночи":
                hour = 0
        return hour, minute
    matches = list(re.finditer(r"(?<!\d)(\d{1,2}):(\d{2})(?!\d)", normalized))
    if matches:
        values = set()
        for match in matches:
            suffix = re.match(r"\s*(утра|дня|вечера|ночи)", normalized[match.end():])
            values.add(converted(int(match[1]), int(match[2]), suffix[1] if suffix else None, match.start()))
    else:
        matches = list(re.finditer(r"\bв\s*(\d{1,2})(?![:\d])(?:\s*(утра|дня|вечера|ночи))?", normalized))
        if not matches:
            # A short answer to «во сколько?» can be just «9 утра».
            match = re.fullmatch(r"\s*(\d{1,2})(?:\s*(утра|дня|вечера|ночи))?[.! ]*", normalized)
            matches = [match] if match else []
        values = set()
        for match in matches:
            values.add(converted(int(match[1]), 0, match[2], match.start()))
    if len(values) > 1:
        raise NeedClarification("Какое одно время использовать для напоминания?")
    if values:
        hour, minute = next(iter(values))
        if not 0 <= hour < 24 or not 0 <= minute < 60:
            raise NeedClarification("Укажите допустимое время от 00:00 до 23:59?")
        return time(hour, minute)
    return None


def weekday(text):
    normalized = normalize_text(text)
    if not (re.search(r"\b(?:в|на|по|с|со|к|кажд\w*)\s+(?:(?:эт\w*|следующ\w*)\s+)?"+DAY_PATTERN+r"\b", normalized)
            or re.fullmatch(r"\s*"+DAY_PATTERN+r"[.! ,]*", normalized)):
        return None
    found = {i for i, pattern in enumerate(WEEKDAY_PATTERNS) if re.search(r"\b(?:" + pattern + r")\b", normalized)}
    if len(found) > 1:
        raise NeedClarification("В какой один день недели нужно повторять напоминание?")
    return next(iter(found)) if found else None


def frequency(text):
    normalized = normalize_text(text)
    if re.search(r"по будням|рабочи\w*\s+дн|каждые\s+\d+|через день|ежегодн|ежечасн|ежеминутн|кажд\w*\s+(год|час|минут)|раз\s+в\s+\d+|дважды|два\s+раза", normalized):
        raise NeedClarification("Поддерживается один из вариантов: разово, ежедневно, еженедельно или ежемесячно. Какой выбрать?")
    if re.search(r"перв\w*|втор\w*|трет\w*|последн\w*", normalized) and weekday(normalized) is not None and re.search(r"месяц", normalized):
        raise NeedClarification("Поддерживается ежемесячное повторение по числу или еженедельное по дню недели. Какой вариант выбрать?")
    for word in re.findall(r"\bеже\w+", normalized):
        if not re.match(r"ежедневн|еженедельн|ежемесячн", word):
            raise NeedClarification("Уточните повторение: ежедневно, еженедельно, ежемесячно или один раз?")
    found = set()
    if re.search(r"ежедневн|каждый\s+день", normalized):
        found.add("DAILY")
    if re.search(r"еженедельн|раз\s+в\s+неделю", normalized) or re.search(r"\b(?:кажд\w*|по)\s+"+DAY_PATTERN+r"\b", normalized):
        found.add("WEEKLY")
    if re.search(r"ежемесячн|раз\s+в\s+месяц|кажд\w*\s+месяц|каждого\s+\d+|последн\w*\s+день\s+месяца", normalized):
        found.add("MONTHLY")
    if re.search(r"разово|один\s+раз", normalized):
        found.add("ONCE")
    if len(found) > 1:
        raise NeedClarification("Какое одно правило повторения выбрать для этого напоминания?")
    return next(iter(found)) if found else None


def month_day(text):
    normalized = normalize_text(text)
    if re.search(r"последн\w*\s+(день|дня)", normalized):
        return 31
    patterns = [r"(?:каждого|ежемесячно)\s+(\d{1,2})(?:-?(?:го|е))?(?:\s+числа)?",
                r"(\d{1,2})(?:-?(?:го|е))?\s+числа", r"кажд\w*\s+месяц\w*\s+(\d{1,2})"]
    values = {int(m[1]) for pattern in patterns for m in re.finditer(pattern, normalized)}
    if len(values) > 1 or values and not 1 <= next(iter(values)) <= 31:
        raise NeedClarification("Какое одно число месяца от 1 до 31 использовать?")
    return next(iter(values)) if values else None


def local_date(text, today):
    normalized = normalize_text(text)
    found = set()
    if re.search(r"\bпослезавтра\b", normalized): found.add(today+timedelta(days=2))
    if re.search(r"\bзавтра\b", normalized): found.add(today+timedelta(days=1))
    if re.search(r"\bсегодня\b", normalized): found.add(today)
    for match in re.finditer(r"\bчерез\s+(\d{1,4})\s+д(?:ень|ня|ней)\b", normalized):
        found.add(today+timedelta(days=int(match[1])))
    try:
        for match in re.finditer(r"\b(\d{4})-(\d{2})-(\d{2})\b", normalized):
            found.add(date(int(match[1]), int(match[2]), int(match[3])))
        for match in re.finditer(r"\b(\d{1,2})\.(\d{1,2})(?:\.(\d{4}))?\b", normalized):
            if re.match(r"\s*(руб|₽|коп|%)", normalized[match.end():]):
                continue
            found.add(date(int(match[3]) if match[3] else today.year, int(match[2]), int(match[1])))
        for month, root in enumerate(MONTHS, 1):
            for match in re.finditer(r"\b(\d{1,2})\s+"+root+r"\w*(?:\s+(\d{4}))?\b", normalized):
                found.add(date(int(match[2]) if match[2] else today.year, month, int(match[1])))
    except ValueError:
        raise NeedClarification("Указана невозможная календарная дата. На какую дату поставить напоминание?")
    if len(found) > 1:
        raise NeedClarification("На какую одну дату поставить напоминание?")
    return next(iter(found)) if found else None


def relative_instant(text, now):
    normalized = normalize_text(text)
    if re.search(r"\bчерез\s+(полтора\s+часа|час\s+с\s+половиной)\b", normalized):
        return now+timedelta(minutes=90)
    compound = re.search(r"\bчерез\s+(\d{1,6})\s+час\w*\s*(?:и\s*)?(\d{1,3})\s+минут\w*", normalized)
    if compound:
        if len(re.findall(r"\bчерез\b", normalized)) > 1:
            raise NeedClarification("Какой один относительный интервал использовать?")
        minutes = int(compound[1])*60+int(compound[2])
        if minutes <= 0:
            raise NeedClarification("Через сколько положительных минут или часов напомнить?")
        return now+timedelta(minutes=minutes)
    if re.search(r"\bчерез\s+полчаса\b", normalized):
        return now+timedelta(minutes=30)
    matches = list(re.finditer(r"\bчерез\s+(\d{1,6}|час|минуту)\s*(час\w*|минут\w*)?", normalized))
    values = []
    for match in matches:
        if match[1] in {"час", "минуту"}:
            number, unit = 1, match[1]
        elif match[2]:
            number, unit = int(match[1]), match[2]
        else:
            continue
        if number <= 0:
            raise NeedClarification("Через сколько положительных минут или часов напомнить?")
        values.append(now+timedelta(minutes=number*(60 if unit.startswith("час") else 1)))
    if len(set(values)) > 1:
        raise NeedClarification("Какой один интервал использовать для напоминания?")
    return values[0] if values else None


def resolve_once(day, local_time, time_zone):
    naive = datetime.combine(day, local_time)
    aware = naive.replace(tzinfo=zone(time_zone), fold=0)
    # fold=0 gives the first ambiguous occurrence; UTC roundtrip shifts a DST gap forward.
    return aware.astimezone(timezone.utc)


def reminder_links(text, context, materials):
    plan = context.get("currentPlan")
    normalized = normalize_text(text)
    step = None
    number = re.search(r"\bшаг\w*\s*№?\s*(\d+)\b|\b(\d+)(?:-?(?:й|го|му|м))?\s+шаг\w*", normalized)
    position = int(next(g for g in number.groups() if g)) if number else None
    if position is None:
        for n, root in enumerate(["перв", "втор", "трет", "четверт", "пят", "шест", "седьм", "восьм", "девят", "десят"], 1):
            if re.search(r"\b"+root+r"\w*\s+шаг\w*", normalized): position = n
        if re.search(r"\bпоследн\w*\s+шаг\w*", normalized) and plan: position = len(plan["steps"])
    if position is not None:
        if not plan or plan["status"] != "ACTIVE":
            raise NeedClarification("С каким шагом текущего активного плана связать напоминание?")
        step = next((s for s in plan["steps"] if s["position"] == position), None)
        if step is None:
            raise NeedClarification("Указанного шага нет в текущем плане. Какой существующий шаг выбрать?")
    known_steps = {str(UUID(s["id"])): s for s in plan["steps"]} if plan and plan["status"] == "ACTIVE" else {}
    known_materials = {str(UUID(mid)): mid for mid in materials}
    known_materials.update({str(UUID(m["id"])): m["id"] for s in known_steps.values() for m in s["materials"]})
    mid = None
    for identifier in re.findall(r"\b[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}\b", text):
        identifier = str(UUID(identifier))
        if identifier in known_steps:
            if step and step["id"] != identifier:
                raise NeedClarification("С каким одним шагом связать напоминание?")
            step = known_steps[identifier]
        elif identifier in known_materials:
            if mid and mid != identifier:
                raise NeedClarification("С каким одним материалом связать напоминание?")
            mid = known_materials[identifier]
        elif not plan or identifier != plan["id"]:
            raise NeedClarification("Этот ID не найден среди доступных материалов и шагов. Какой материал или шаг выбрать?")
    if step and mid and mid not in {m["id"] for m in step["materials"]}:
        raise NeedClarification("Указанный материал не входит в выбранный шаг. Какой материал этого шага использовать?")
    if step is None and re.search(r"\bшаг(?:а|у|е|ом)?\b", normalized):
        raise NeedClarification("Какой существующий шаг текущего плана выбрать для напоминания?")
    return mid, step["id"] if step else None


def draft_reminder(message, context, now, materials=None):
    history_layers = pending_layers(message, context)
    previous = history_layers[-1] if history_layers else None
    layers = [message, *history_layers]
    full = "\n".join(reversed(layers))
    try:
        if any(re.search(r"\bили\b", normalize_text(layer)) for layer in layers):
            raise NeedClarification("Какой один вариант даты, времени и повторения выбрать?")
        tz = next((value for layer in layers if (value := explicit_zone(layer))), None) or context["profile"].get("timeZone")
        if tz is None:
            raise NeedClarification("В каком часовом поясе поставить напоминание, например Europe/Moscow?")
        local_now = now.astimezone(zone(tz))
        kind = next((value for layer in layers if (value := frequency(layer))), None) or "ONCE"
        relative = next((value for layer in layers if (value := relative_instant(layer, now))), None)
        local_clock = next((value for layer in layers if (value := clock(layer))), None)
        day = next((value for layer in layers if (value := local_date(layer, local_now.date()))), None)
        wd = next((value for layer in layers if (value := weekday(layer)) is not None), None)
        if relative:
            if kind != "ONCE":
                raise NeedClarification("Для повторяющегося напоминания укажите точное время суток HH:mm?")
            if local_clock is not None:
                raise NeedClarification("Использовать относительный интервал или указанное время суток?")
            local_clock = relative.astimezone(zone(tz)).time().replace(tzinfo=None)
            day = relative.astimezone(zone(tz)).date()
        if local_clock is None:
            raise NeedClarification("Во сколько напомнить? Укажите время в формате HH:mm.")
        if kind == "ONCE":
            if day is None and wd is not None:
                day = local_now.date()+timedelta(days=(wd-local_now.weekday()) % 7)
                if resolve_once(day, local_clock, tz) <= now:
                    day += timedelta(days=7)
            if day is None:
                raise NeedClarification("На какую дату поставить разовое напоминание?")
            at = relative or resolve_once(day, local_clock, tz)
            schedule = {"type": "ONCE", "at": at.isoformat().replace("+00:00", "Z"), "timeZone": tz}
        else:
            schedule = {"type": kind, "localTime": local_clock.strftime("%H:%M"),
                        "startDate": (day or local_now.date()).isoformat(), "timeZone": tz}
            if kind == "WEEKLY":
                if wd is None:
                    raise NeedClarification("В какой день недели повторять напоминание?")
                schedule["dayOfWeek"] = DAYS[wd]
            if kind == "MONTHLY":
                md = next((value for layer in layers if (value := month_day(layer))), None)
                if md is None:
                    raise NeedClarification("Какого числа месяца повторять напоминание, от 1 до 31?")
                schedule["dayOfMonth"] = md
        try:
            validate_schedule(schedule, now)
        except ValueError:
            raise NeedClarification("Указанное разовое время уже прошло или расписание некорректно. На какую будущую дату и время напомнить?")
        mid, sid = reminder_links(full, context, materials or {})
        title = re.sub(r"https?://\S+", "", previous or message).strip()
        title = re.sub(r"^\s*(?:напомни(?:те)?|напоминай)(?:\s+мне)?\s*", "", title, flags=re.I)
        title = re.sub(r"\b(?:завтра|послезавтра|сегодня|ежедневно|еженедельно|ежемесячно)\b", "", title, flags=re.I)
        title = re.sub(r"\bв\s*\d{1,2}(?::\d{2})?(?:\s*(?:утра|вечера|дня|ночи))?", "", title, flags=re.I)
        title = re.sub(r"по\s+Москве|Europe/Moscow|\bUTC[+-]?\d*\b", "", title, flags=re.I)
        title = " ".join(title.split()).strip(" ,.!:;")[:160] or "Продолжить обучение"
        if re.search(r"```|\bSELECT\b.{0,80}\bFROM\b|\bDROP TABLE\b|\bINSERT INTO\b", title, re.I):
            raise NeedClarification("Какой заголовок напоминания указать без кода и ссылок?")
        if kind == "ONCE":
            local_at = at.astimezone(zone(tz))
            human = f"{local_at:%d.%m.%Y} в {local_at:%H:%M} ({tz})"
        else:
            human = {"DAILY": "ежедневно", "WEEKLY": "еженедельно, "+WEEKDAY_LABELS[wd] if wd is not None else "еженедельно",
                     "MONTHLY": f"ежемесячно {schedule.get('dayOfMonth')}-го числа"}[kind]
            human += f" в {schedule['localTime']} ({tz}), начиная с {schedule['startDate']}"
            if kind == "MONTHLY" and schedule["dayOfMonth"] >= 29:
                human += "; в коротком месяце — в последний день"
        return {"status": "ACTION_PROPOSAL", "answer": f"Предлагаю напомнить {human}. Сохранение и отправка — после подтверждения в приложении.",
                "reminderProposal": {"title": title, "schedule": schedule, "materialId": mid, "planStepId": sid}}
    except NeedClarification as error:
        return {"status": "CLARIFY", "answer": str(error), "reminderProposal": None}

"""Проверки публичного контракта действий. Ничего не сохраняют и не отправляют."""
from datetime import date, datetime, timezone
import re
from uuid import UUID
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

PROFILE_ENUMS = {
    "businessStage": ["IDEA", "LAUNCH", "OPERATING", "GROWTH"],
    "experience": ["BEGINNER", "EXPERIENCED"],
    "taxRegime": ["NPD", "USN_6", "USN_15", "PATENT", "OSNO", "AUSN"],
}
PROFILE_LIMITS = {"industry": 100, "currentTask": 500, "timeZone": 64}
PROFILE_FIELDS = set(PROFILE_ENUMS) | set(PROFILE_LIMITS)
DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"]


def text(value, limit, label):
    if not isinstance(value, str) or not 1 <= len(value.strip()) <= limit:
        raise ValueError(f"Неверное поле {label}")
    return value


def uuid(value):
    if not isinstance(value, str):
        raise ValueError("ID должен быть UUID")
    UUID(value)
    return value


def zone(value):
    text(value, 64, "timeZone")
    try:
        return ZoneInfo(value)
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise ValueError("Нужна существующая IANA timeZone") from error


def utc_now(value=None):
    if value is None:
        return datetime.now(timezone.utc)
    if not isinstance(value, str):
        raise ValueError("now должен быть строкой UTC")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset().total_seconds() != 0:
        raise ValueError("now должен содержать UTC Z или +00:00")
    return parsed.astimezone(timezone.utc)


def validate_profile(value):
    if not isinstance(value, dict) or set(value)-PROFILE_FIELDS:
        raise ValueError("Неизвестные поля профиля")
    for key, field in value.items():
        if field is None:
            continue
        if key in PROFILE_ENUMS:
            if not isinstance(field, str) or field not in PROFILE_ENUMS[key]:
                raise ValueError("Неверное поле профиля " + key)
        else:
            text(field, PROFILE_LIMITS[key], key)
            if key == "timeZone":
                zone(field)
    return value


def profile_schema():
    properties = {key: {"enum": [None, *values]} for key, values in PROFILE_ENUMS.items()}
    properties.update({key: {"type": ["string", "null"], "minLength": 1, "maxLength": limit}
                       for key, limit in PROFILE_LIMITS.items()})
    return {"type": "object", "additionalProperties": False, "properties": properties}


def validate_current_plan(plan):
    if plan is None:
        return
    required = {"id", "goal", "status", "version", "steps"}
    if not isinstance(plan, dict) or not required <= set(plan) or set(plan)-required-{"createdAt", "updatedAt"}:
        raise ValueError("Неверная структура currentPlan")
    uuid(plan["id"]); text(plan["goal"], 250, "goal")
    for key in ["createdAt", "updatedAt"]:
        if key in plan:
            utc_now(plan[key])
    if plan["status"] not in {"ACTIVE", "ARCHIVED"} or type(plan["version"]) is not int or plan["version"] < 0:
        raise ValueError("Неверный статус/версия currentPlan")
    steps = plan["steps"]
    if not isinstance(steps, list) or not 3 <= len(steps) <= 5:
        raise ValueError("currentPlan содержит 3–5 шагов")
    ids, positions = set(), set()
    for step in steps:
        if not isinstance(step, dict) or set(step) != {"id", "position", "title", "description", "status", "materials"}:
            raise ValueError("Неверная структура шага currentPlan")
        sid = str(UUID(uuid(step["id"]))); text(step["title"], 160, "title"); text(step["description"], 600, "description")
        if sid in ids or type(step["position"]) is not int or step["position"] in positions or step["status"] not in {"TODO", "DONE"}:
            raise ValueError("Неверные ID/позиции/статусы шагов")
        ids.add(sid); positions.add(step["position"])
        cards = step["materials"]
        if not isinstance(cards, list) or not 1 <= len(cards) <= 2:
            raise ValueError("Шаг содержит 1–2 материала")
        mids = set()
        for card in cards:
            fields = {"id", "title", "kind", "description", "isPaid", "duration", "url"}
            if not isinstance(card, dict) or not fields <= set(card) or set(card)-fields-{"recommendationReason"}:
                raise ValueError("Неверная карточка материала currentPlan")
            mid = str(UUID(uuid(card["id"]))); text(card["title"], 250, "material title")
            if mid in mids or type(card["isPaid"]) is not bool or card["kind"] not in {"ARTICLE", "COURSE", "LESSON", "DOCUMENT", "WEBINAR", "PODCAST", "GLOSSARY", "CALCULATOR"}:
                raise ValueError("Неверная карточка материала")
            mids.add(mid)
            for key in ["description", "duration", "recommendationReason"]:
                if card.get(key) is not None and (not isinstance(card[key], str) or len(card[key]) > 2500):
                    raise ValueError("Неверное текстовое поле карточки")
            url = urlsplit(card["url"])
            if url.scheme != "https" or not url.hostname or url.username or url.password:
                raise ValueError("Карточка должна иметь HTTPS URL без credentials")
    if positions != set(range(1, len(steps)+1)):
        raise ValueError("Позиции шагов должны идти от 1 без пропусков")


def schedule_schema():
    variants = []
    for kind in ["ONCE", "DAILY", "WEEKLY", "MONTHLY"]:
        properties = {"type": {"const": kind}, "timeZone": {"type": "string", "minLength": 1, "maxLength": 64}}
        if kind == "ONCE":
            properties["at"] = {"type": "string"}
        else:
            properties.update(localTime={"type": "string", "pattern": "^([01][0-9]|2[0-3]):[0-5][0-9]$"},
                              startDate={"type": "string"})
            if kind == "WEEKLY":
                properties["dayOfWeek"] = {"enum": DAYS}
            if kind == "MONTHLY":
                properties["dayOfMonth"] = {"type": "integer", "minimum": 1, "maximum": 31}
        variants.append({"type": "object", "additionalProperties": False,
                         "required": list(properties), "properties": properties})
    return {"anyOf": variants}


def validate_schedule(schedule, now):
    if not isinstance(schedule, dict) or schedule.get("type") not in {"ONCE", "DAILY", "WEEKLY", "MONTHLY"}:
        raise ValueError("Неизвестный тип расписания")
    kind = schedule["type"]
    expected = {"type", "timeZone", "at"} if kind == "ONCE" else {"type", "timeZone", "localTime", "startDate"}
    expected |= {"dayOfWeek"} if kind == "WEEKLY" else {"dayOfMonth"} if kind == "MONTHLY" else set()
    if set(schedule) != expected:
        raise ValueError("Неверный набор полей расписания")
    zone(schedule["timeZone"])
    if kind == "ONCE":
        at = utc_now(schedule["at"])
        if at <= now:
            raise ValueError("ONCE.at должен быть в будущем")
    else:
        if not isinstance(schedule["localTime"], str) or not re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", schedule["localTime"]):
            raise ValueError("Неверное localTime")
        if not isinstance(schedule["startDate"], str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", schedule["startDate"]):
            raise ValueError("Неверное startDate")
        date.fromisoformat(schedule["startDate"])
        if kind == "WEEKLY" and schedule["dayOfWeek"] not in DAYS:
            raise ValueError("Неверный день недели")
        if kind == "MONTHLY" and (type(schedule["dayOfMonth"]) is not int or not 1 <= schedule["dayOfMonth"] <= 31):
            raise ValueError("Неверное число месяца")


def validate_proposals(result, catalog, context, now):
    patch = result["profilePatch"]
    validate_profile(patch)
    plan, reminder = result["planProposal"], result["reminderProposal"]
    if plan is not None and reminder is not None:
        raise ValueError("План и напоминание одновременно не предлагаются")
    if plan is not None:
        if result["status"] != "ANSWER" or not isinstance(plan, dict) or set(plan) != {"goal", "steps"}:
            raise ValueError("План допустим только в ANSWER")
        text(plan["goal"], 250, "goal")
        if not isinstance(plan["steps"], list) or not 3 <= len(plan["steps"]) <= 5:
            raise ValueError("В плане должны быть 3–5 шагов")
        allowed = {m["id"] for m in catalog}
        for step in plan["steps"]:
            if not isinstance(step, dict) or set(step) != {"title", "description", "materialIds"}:
                raise ValueError("Неверная структура предложения шага")
            text(step["title"], 160, "title"); text(step["description"], 600, "description")
            ids = step["materialIds"]
            if not isinstance(ids, list) or not 1 <= len(ids) <= 2 or any(not isinstance(i, str) or i not in allowed for i in ids) or len(set(ids)) != len(ids):
                raise ValueError("Шаг ссылается на неизвестные/повторные материалы")
    if result["status"] == "ACTION_PROPOSAL" and reminder is None:
        raise ValueError("ACTION_PROPOSAL требует reminderProposal")
    if reminder is not None:
        if result["status"] != "ACTION_PROPOSAL" or not isinstance(reminder, dict) or set(reminder) != {"title", "schedule", "materialId", "planStepId"}:
            raise ValueError("Неверное предложение напоминания")
        text(reminder["title"], 160, "reminder title")
        validate_schedule(reminder["schedule"], now)
        current = context.get("currentPlan") if context else None
        steps = {s["id"]: s for s in current["steps"]} if current and current["status"] == "ACTIVE" else {}
        mids = {m["id"] for m in catalog} | {m["id"] for s in steps.values() for m in s["materials"]}
        sid, mid = reminder["planStepId"], reminder["materialId"]
        if sid is not None and sid not in steps:
            raise ValueError("Шаг напоминания не принадлежит текущему активному плану")
        if mid is not None and mid not in mids:
            raise ValueError("Неизвестный материал напоминания")
        if sid is not None and mid is not None and mid not in {m["id"] for m in steps[sid]["materials"]}:
            raise ValueError("Материал не входит в связанный шаг")
    for value in [result["answer"], *(r["reason"] for r in result["recommendations"]),
                  *(v for v in patch.values() if isinstance(v, str)), *([reminder["title"]] if reminder else []),
                  *([plan["goal"]] + [s[k] for s in plan["steps"] for k in ["title", "description"]] if plan else [])]:
        if re.search(r"https?://|```|\bSELECT\b.{0,100}\bFROM\b|\bINSERT INTO\b|\bDROP TABLE\b", value, re.I | re.S):
            raise ValueError("В ответе/предложении запрещены URL, SQL и Markdown fences")
    if re.search(r"напоминание\s+(создано|сохранено|поставлено|установлено)|профиль\s+(обновл[её]н|сохран[её]н)|план\s+(сохран[её]н|замен[её]н)|я\s+(создал|сохранил|поставил)|буду\s+напоминать", result["answer"], re.I):
        raise ValueError("Нельзя утверждать, что действие выполнено")

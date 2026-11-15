package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.model.AssistantResponse;
import com.alfatgbotik.reminder.model.ReminderSchedule;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

@Component
public class ReminderInterpreter {

    private static final String[] DAYS = {"понедельник(?:а|у|е|ам|ами|ах)?", "вторник(?:а|у|е|ам|ами|ах)?", "сред(?:а|у|ы|е|ам|ами|ах)",
            "четверг(?:а|у|е|ам|ами|ах)?", "пятниц(?:а|у|ы|е|ам|ами|ах)", "суббот(?:а|у|ы|е|ам|ами|ах)", "воскресень(?:е|я|ю|ем|ям|ями|ях)"};
    private static final String[] MONTHS = {"январ", "феврал", "март", "апрел", "мая", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр"};
    private final JsonMapper json;

    public ReminderInterpreter(JsonMapper json) {
        this.json = json;
    }

    public static boolean requested(String text) {
        String lower = normal(text);
        return !has(lower, "^\\s*(как|зачем|почему|что)\\b|^\\s*напомни\\s*(?:мне\\s*)?[, ]*\\s*(что такое|определение|чем отличается)")
                && has(lower, "\\bнапомни\\b|\\bнапоминай\\b|\\b(?:создай|поставь|добавь|предложи)\\b.{0,35}напоминани");
    }

    public boolean applies(AssistantContext context) {
        return requested(context.message()) || !layers(context).isEmpty();
    }

    public AssistantResponse interpret(AssistantContext context, JsonNode patch) {
        List<String> previous = layers(context);
        List<String> layers = new ArrayList<>(List.of(context.message()));
        layers.addAll(previous);
        try {
            if (has(context.message(), "\\bили\\b")) {
                throw clarify("Какой один вариант даты и времени выбрать?");
            }
            String zone = first(layers, ReminderInterpreter::explicitZone);
            if (zone == null) {
                zone = context.profile().timeZone();
            }
            if (zone == null) {
                throw clarify("В каком часовом поясе поставить напоминание, например Europe/Moscow?");
            }
            var localNow = context.now().atZone(ZoneId.of(zone));
            var type = first(layers, this::frequency);
            if (type == null) {
                type = ReminderSchedule.Type.ONCE;
            }
            LocalTime time = first(layers, this::clock);
            LocalDate date = first(layers, text -> date(text, localNow.toLocalDate()));
            DayOfWeek weekday = first(layers, this::weekday);
            Instant relative = first(layers, text -> relative(text, context.now()));
            ReminderSchedule schedule;
            if (relative != null) {
                if (type != ReminderSchedule.Type.ONCE || time != null || date != null || weekday != null) {
                    throw clarify("Использовать относительный интервал или точную дату и время?");
                }
                schedule = new ReminderSchedule(type, relative, zone, null, null, null, null);
            } else {
                if (time == null) {
                    throw clarify("Во сколько напомнить? Укажи время в формате HH:mm.");
                }
                if (type == ReminderSchedule.Type.ONCE) {
                    if (date == null && weekday != null) {
                        date = localNow.toLocalDate().plusDays(Math.floorMod(weekday.getValue() - localNow.getDayOfWeek().getValue(), 7));
                        if (!date.atTime(time).atZone(ZoneId.of(zone)).toInstant().isAfter(context.now())) {
                            date = date.plusWeeks(1);
                        }
                    }
                    if (date == null) {
                        throw clarify("На какую дату поставить разовое напоминание?");
                    }
                    schedule = new ReminderSchedule(type, date.atTime(time).atZone(ZoneId.of(zone)).toInstant(), zone, null, null, null, null);
                } else {
                    if (type == ReminderSchedule.Type.WEEKLY && weekday == null) {
                        throw clarify("В какой один день недели повторять напоминание?");
                    }
                    Integer monthDay = type == ReminderSchedule.Type.MONTHLY ? first(layers, this::monthDay) : null;
                    if (type == ReminderSchedule.Type.MONTHLY && monthDay == null) {
                        throw clarify("Какого числа месяца повторять напоминание, от 1 до 31?");
                    }
                    schedule = new ReminderSchedule(type, null, zone, time, date == null ? localNow.toLocalDate() : date,
                            type == ReminderSchedule.Type.WEEKLY ? weekday : null, monthDay);
                }
            }
            schedule.requireFuture(context.now());
            UUID stepId = null;
            UUID materialId = null;
            String full = String.join(" ", layers);
            var step = pattern("(?:шаг(?:е|а)?\\s*(\\d+)|(?:о|об|про)\\s+(первом|втором|третьем|четвертом|пятом)\\s+шаге)").matcher(full);
            if (step.find()) {
                int position = step.group(1) != null ? Integer.parseInt(step.group(1))
                        : List.of("первом", "втором", "третьем", "четвертом", "пятом").indexOf(normal(step.group(2))) + 1;
                var linked = context.currentPlan() == null ? null : context.currentPlan().steps().stream()
                        .filter(item -> item.position() == position).findFirst().orElse(null);
                if (linked == null || !context.currentPlan().status().equals("ACTIVE")) {
                    throw clarify("Какой шаг текущего активного плана выбрать для напоминания?");
                }
                stepId = linked.id();
            }
            var material = pattern("материал\\p{L}*\\s+([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})").matcher(full);
            if (material.find()) {
                materialId = UUID.fromString(material.group(1));
                UUID selected = materialId;
                boolean known = context.catalog().stream().anyMatch(item -> item.id().equals(selected))
                        || (context.currentPlan() != null && context.currentPlan().steps().stream()
                        .anyMatch(item -> item.materials().stream().anyMatch(card -> card.id().equals(selected))));
                if (!known) {
                    throw clarify("Какой доступный материал из текущего плана использовать?");
                }
            }
            if (stepId != null && materialId != null) {
                UUID selectedStep = stepId;
                UUID selectedMaterial = materialId;
                if (context.currentPlan().steps().stream().filter(item -> item.id().equals(selectedStep))
                        .noneMatch(item -> item.materials().stream().anyMatch(card -> card.id().equals(selectedMaterial)))) {
                    throw clarify("Материал не входит в этот шаг. Какой материал шага выбрать?");
                }
            }
            String original = previous.isEmpty() ? context.message() : previous.getLast();
            String title = original.replaceAll("(?iuU)^\\s*(?:напомни(?:те)?|напоминай)(?:\\s+мне)?\\s*", "")
                    .replaceAll("(?iuU)\\b(?:завтра|послезавтра|сегодня|ежедневно|еженедельно|ежемесячно)\\b", "")
                    .replaceAll("(?iuU)\\bв\\s*\\d{1,2}(?::\\d{2})?(?:\\s*(?:утра|вечера|дня|ночи))?", "")
                    .replaceAll("(?iuU)\\bчерез\\s+\\d+\\s+(?:минут\\p{L}*|час\\p{L}*)", "")
                    .replaceAll("(?iuU)по\\s+Москве|Europe/Moscow|\\bUTC[+-]?\\d*\\b|https?://\\S+", "")
                    .replaceAll("\\s+", " ").strip().replaceAll("^[ ,.!:;]+|[ ,.!:;]+$", "");
            if (title.isBlank()) {
                title = "Продолжить обучение";
            }
            title = title.substring(0, title.offsetByCodePoints(0, Math.min(160, title.codePointCount(0, title.length()))));
            if (has(title, "```|\\bselect\\b.*\\bfrom\\b|drop table|insert into")) {
                throw clarify("Какой заголовок напоминания указать без кода и ссылок?");
            }
            var proposal = json.createObjectNode().put("title", title);
            proposal.set("schedule", json.valueToTree(schedule));
            proposal.set("materialId", json.valueToTree(materialId));
            proposal.set("planStepId", json.valueToTree(stepId));
            String human = schedule.type() == ReminderSchedule.Type.ONCE
                    ? schedule.at().atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                    : schedule.type() + " в " + schedule.localTime().format(DateTimeFormatter.ofPattern("HH:mm"));
            return new AssistantResponse("0.1.0", AssistantResponse.Status.ACTION_PROPOSAL,
                    "Предлагаю напоминание «" + title + "»: " + human + " (" + zone + "). Сохраню после подтверждения.",
                    List.of(), List.of(), patch, json.valueToTree(null), proposal);
        } catch (Clarification exception) {
            return ActionResponses.clarify(json, exception.getMessage(), patch);
        } catch (RuntimeException exception) {
            return ActionResponses.clarify(json, "Дата или время некорректны либо уже прошли. На какую будущую дату и время напомнить?", patch);
        }
    }

    public static String explicitZone(String text) {
        Set<String> zones = new HashSet<>();
        var matcher = Pattern.compile("(?:Africa|America|Antarctica|Arctic|Asia|Atlantic|Australia|Europe|Indian|Pacific|Etc)/[A-Za-z_+\\-0-9]+(?:/[A-Za-z_+\\-0-9]+)?").matcher(text);
        while (matcher.find()) {
            zones.add(matcher.group());
        }
        if (has(text, "\\bне\\s+(по\\s+москве|московск|[a-z]+/[a-z_+/\\-]+|utc\\b)|мск\\s*[+-]|по\\s+москве\\s*[+-]")) {
            throw clarify("Какой один подтверждённый часовой пояс IANA использовать?");
        }
        var offset = pattern("\\butc\\s*([+-]\\d{1,2})(?::(\\d{2}))?").matcher(text);
        if (offset.find()) {
            if (offset.group(2) != null && !offset.group(2).equals("00")) {
                throw clarify("Укажи часовой пояс IANA, например Asia/Kolkata?");
            }
            int hours = Integer.parseInt(offset.group(1));
            zones.add(hours == 0 ? "UTC" : "Etc/GMT" + (hours > 0 ? "-" : "+") + Math.abs(hours));
        } else if (has(text, "\\butc\\b")) {
            zones.add("UTC");
        }
        if (has(text, "по москве|московск\\p{L}*\\s+врем|часов\\p{L}*\\s+пояс.{0,15}московск|\\bмск\\b|\\bmsk\\b")
                || normal(text).strip().matches("москва[ .!?]*|московское(?: время)?[ .!?]*")) {
            zones.add("Europe/Moscow");
        }
        if (zones.size() > 1 || zones.stream().anyMatch(zone -> !ZoneId.getAvailableZoneIds().contains(zone))) {
            throw clarify("Какой один существующий часовой пояс IANA использовать?");
        }
        return zones.stream().findFirst().orElse(null);
    }

    private static List<String> layers(AssistantContext context) {
        String message = context.message();
        var history = context.history();
        if (requested(message) || has(message, "^\\s*напомни\\s*(?:мне\\s*)?[, ]*\\s*(что такое|определение|чем отличается)")
                || has(message, "^\\s*(составь|создай|измени|обнови|удали|отмени|очисти|как|почему|зачем|что такое)\\b")
                || has(message, "у меня|я новичок") || history.isEmpty()) {
            return List.of();
        }
        var last = history.getLast();
        if (!last.role().equals("ASSISTANT") || !last.text().contains("?")
                || !has(last.text(), "дат|время|во сколько|пояс|числ|день недели|шаг")) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int i = history.size() - 2; i >= 0; i--) {
            var item = history.get(i);
            if (!item.role().equals("USER")) {
                continue;
            }
            if (requested(item.text())) {
                result.add(item.text());
                return result;
            }
            if (item.text().length() > 300 || has(item.text(), "^\\s*(как|почему|зачем|что такое|составь|создай|отмени|удали)\\b")) {
                break;
            }
            result.add(item.text());
        }
        return List.of();
    }

    public static String confirmedZone(AssistantContext context) {
        List<String> values = new ArrayList<>(List.of(context.message()));
        values.addAll(layers(context));
        for (String text : values) {
            String zone = explicitZone(text);
            if (zone != null) return zone;
        }
        return context.profile().timeZone();
    }

    private ReminderSchedule.Type frequency(String text) {
        if (has(text, "по будням|рабочи\\p{L}*\\s+дн|каждые\\s+\\d+|через день|ежегодн|ежечасн|ежеминутн|кажд\\p{L}*\\s+(год|час|минут)|раз\\s+в\\s+\\d+|дважды")) {
            throw clarify("Какой вариант выбрать: разово, ежедневно, еженедельно или ежемесячно?");
        }
        Set<ReminderSchedule.Type> types = new HashSet<>();
        if (has(text, "ежедневн|каждый\\s+день")) types.add(ReminderSchedule.Type.DAILY);
        if (has(text, "еженедельн|раз\\s+в\\s+неделю|(?:кажд\\p{L}*|по)\\s+(?:" + String.join("|", DAYS) + ")\\b")) types.add(ReminderSchedule.Type.WEEKLY);
        if (has(text, "ежемесячн|раз\\s+в\\s+месяц|кажд\\p{L}*\\s+месяц|каждого\\s+\\d+|последн\\p{L}*\\s+день\\s+месяца")) types.add(ReminderSchedule.Type.MONTHLY);
        if (has(text, "разово|один\\s+раз")) types.add(ReminderSchedule.Type.ONCE);
        if (types.size() > 1) throw clarify("Какое одно правило повторения выбрать?");
        return types.stream().findFirst().orElse(null);
    }

    private DayOfWeek weekday(String text) {
        Set<DayOfWeek> days = new HashSet<>();
        for (int i = 0; i < DAYS.length; i++) {
            if (has(text, "\\b" + DAYS[i] + "\\b")) {
                days.add(DayOfWeek.of(i + 1));
            }
        }
        if (days.size() > 1) throw clarify("В какой один день недели повторять напоминание?");
        return days.stream().findFirst().orElse(null);
    }

    private Integer monthDay(String text) {
        if (has(text, "последн\\p{L}*\\s+(день|дня)")) return 31;
        var match = pattern("(?:каждого|ежемесячно)\\s+(\\d{1,2})(?:-?(?:го|е))?(?:\\s+числа)?|(\\d{1,2})(?:-?(?:го|е))?\\s+числа|кажд\\p{L}*\\s+месяц\\p{L}*\\s+(\\d{1,2})").matcher(text);
        Set<Integer> values = new HashSet<>();
        while (match.find()) {
            values.add(Integer.parseInt(match.group(1) != null ? match.group(1) : match.group(2) != null ? match.group(2) : match.group(3)));
        }
        if (values.size() > 1 || values.stream().anyMatch(value -> value < 1 || value > 31)) throw clarify("Какое одно число месяца от 1 до 31 выбрать?");
        return values.stream().findFirst().orElse(null);
    }

    private LocalTime clock(String text) {
        var match = pattern("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)\\s*(утра|дня|вечера|ночи)?").matcher(text);
        Set<LocalTime> values = new HashSet<>();
        while (match.find()) values.add(time(text, match.start(), Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), match.group(3)));
        if (values.isEmpty()) {
            match = pattern("\\bв\\s*(\\d{1,2})(?![:\\d])(?:\\s*(утра|дня|вечера|ночи))?").matcher(text);
            while (match.find()) values.add(time(text, match.start(), Integer.parseInt(match.group(1)), 0, match.group(2)));
        }
        if (values.isEmpty()) {
            match = pattern("^\\s*(\\d{1,2})(?:\\s*(утра|дня|вечера|ночи))?[.! ]*$").matcher(text);
            if (match.matches()) values.add(time(text, 0, Integer.parseInt(match.group(1)), 0, match.group(2)));
        }
        if (values.size() > 1) throw clarify("Какое одно время использовать?");
        return values.stream().findFirst().orElse(null);
    }

    private LocalTime time(String text, int at, int hour, int minute, String period) {
        if (period == null) {
            var prefix = pattern("(вечером|утром|днем|ночью)\\s*(?:в\\s*)?$").matcher(normal(text.substring(Math.max(0, at - 25), at)));
            if (prefix.find()) period = MapPeriod.convert(prefix.group(1));
        }
        if (period != null) {
            period = normal(period);
            if ((hour == 12 && period.matches("утра|вечера")) || (hour > 12 && period.matches("утра|ночи"))) throw clarify("Укажи точное время в формате 24 часов HH:mm?");
            if (hour >= 1 && hour <= 12 && period.matches("вечера|дня")) hour = hour % 12 + 12;
            if (hour == 12 && period.equals("ночи")) hour = 0;
        }
        return LocalTime.of(hour, minute);
    }

    private LocalDate date(String text, LocalDate today) {
        Set<LocalDate> dates = new HashSet<>();
        if (has(text, "\\bпослезавтра\\b")) dates.add(today.plusDays(2));
        if (has(text, "\\bзавтра\\b")) dates.add(today.plusDays(1));
        if (has(text, "\\bсегодня\\b")) dates.add(today);
        var relative = pattern("\\bчерез\\s+(\\d{1,4})\\s+д(?:ень|ня|ней)\\b").matcher(text);
        while (relative.find()) dates.add(today.plusDays(Integer.parseInt(relative.group(1))));
        var iso = pattern("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").matcher(text);
        while (iso.find()) dates.add(LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3))));
        var dotted = pattern("\\b(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?\\b").matcher(text);
        while (dotted.find()) dates.add(LocalDate.of(dotted.group(3) == null ? today.getYear() : Integer.parseInt(dotted.group(3)), Integer.parseInt(dotted.group(2)), Integer.parseInt(dotted.group(1))));
        for (int i = 0; i < MONTHS.length; i++) {
            var named = pattern("\\b(\\d{1,2})\\s+" + MONTHS[i] + "\\p{L}*(?:\\s+(\\d{4}))?\\b").matcher(text);
            while (named.find()) dates.add(LocalDate.of(named.group(2) == null ? today.getYear() : Integer.parseInt(named.group(2)), i + 1, Integer.parseInt(named.group(1))));
        }
        if (dates.size() > 1) throw clarify("На какую одну дату поставить напоминание?");
        return dates.stream().findFirst().orElse(null);
    }

    private Instant relative(String text, Instant now) {
        if (has(text, "через\\s+(полтора\\s+часа|час\\s+с\\s+половиной)")) return now.plusSeconds(5400);
        if (has(text, "через\\s+полчаса")) return now.plusSeconds(1800);
        var compound = pattern("через\\s+(\\d{1,6})\\s+час\\p{L}*\\s*(?:и\\s*)?(\\d{1,3})\\s+минут\\p{L}*").matcher(text);
        if (compound.find()) return now.plusSeconds((Long.parseLong(compound.group(1)) * 60 + Long.parseLong(compound.group(2))) * 60);
        Set<Instant> times = new HashSet<>();
        var match = pattern("\\bчерез\\s+(\\d{1,6}|час|минуту)\\s*(час\\p{L}*|минут\\p{L}*)?").matcher(text);
        while (match.find()) {
            String unit = match.group(2) == null ? match.group(1) : match.group(2);
            if (!unit.startsWith("час") && !unit.startsWith("минут")) continue;
            long count = match.group(1).matches("\\d+") ? Long.parseLong(match.group(1)) : 1;
            if (count == 0) throw clarify("Через сколько положительных минут или часов напомнить?");
            times.add(now.plusSeconds(count * (unit.startsWith("час") ? 3600 : 60)));
        }
        if (times.size() > 1) throw clarify("Какой один интервал использовать для напоминания?");
        return times.stream().findFirst().orElse(null);
    }

    private <T> T first(List<String> layers, Function<String, T> read) {
        for (String layer : layers) {
            T value = read.apply(layer);
            if (value != null) return value;
        }
        return null;
    }

    private static String normal(String text) { return text.toLowerCase(Locale.ROOT).replace('ё', 'е'); }
    private static Pattern pattern(String regex) { return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS); }
    private static boolean has(String text, String regex) { return pattern(regex).matcher(text).find(); }
    private static Clarification clarify(String question) { return new Clarification(question); }
    private static class Clarification extends RuntimeException { Clarification(String text) { super(text); } }
    private static class MapPeriod {
        static String convert(String period) { return switch (period) { case "вечером" -> "вечера"; case "утром" -> "утра"; case "днем" -> "дня"; default -> "ночи"; }; }
    }
}

package com.alfatgbotik.reminder.scheduling;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
public class ReminderSchedulingConfiguration {

    @Bean
    public JobDetail reminderDispatchJob() {
        return JobBuilder.newJob(ReminderDispatchJob.class).withIdentity("dispatch", "reminders")
                .storeDurably().build();
    }

    @Bean
    @Order(3)
    public ApplicationRunner startReminderScheduler(Scheduler scheduler) {
        return arguments -> scheduler.start();
    }

    @Bean
    public Trigger reminderDispatchTrigger(JobDetail reminderDispatchJob) {
        return TriggerBuilder.newTrigger().withIdentity("dispatch", "reminders").forJob(reminderDispatchJob)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(5)
                        .repeatForever().withMisfireHandlingInstructionNextWithRemainingCount()).build();
    }
}

package com.alfatgbotik.reminder.scheduling;

import com.alfatgbotik.reminder.service.ReminderDeliveryService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

@DisallowConcurrentExecution
public class ReminderDispatchJob extends QuartzJobBean {

    @Autowired
    private ReminderDeliveryService deliveries;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        deliveries.dispatchDue();
    }
}

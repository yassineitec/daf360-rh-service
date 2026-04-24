package com.daf360.rh.config;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String HR_EXCHANGE   = "daf360.hr";
    public static final String EMPLOYEE_EVENTS_QUEUE = "hr.employee.events";
    public static final String LEAVE_EVENTS_QUEUE    = "hr.leave.events";

    @Bean
    public DirectExchange hrExchange() {
        return new DirectExchange(HR_EXCHANGE, true, false);
    }

    @Bean
    public Queue employeeEventsQueue() {
        return new Queue(EMPLOYEE_EVENTS_QUEUE, true);
    }

    @Bean
    public Queue leaveEventsQueue() {
        return new Queue(LEAVE_EVENTS_QUEUE, true);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf) {
        RabbitTemplate tmpl = new RabbitTemplate(cf);
        tmpl.setMessageConverter(messageConverter());
        return tmpl;
    }
}

package com.tasktracker.websocket;

import com.tasktracker.dto.TaskEventMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public void publishCreated(Long taskId) {
        publish("CREATE", taskId);
    }

    public void publishUpdated(Long taskId) {
        publish("UPDATE", taskId);
    }

    public void publishDeleted(Long taskId) {
        publish("DELETE", taskId);
    }

    private void publish(String action, Long taskId) {
        TaskEventMessage message = TaskEventMessage.builder()
                .action(action)
                .taskId(taskId)
                .build();
        messagingTemplate.convertAndSend("/topic/tasks", message);
    }
}

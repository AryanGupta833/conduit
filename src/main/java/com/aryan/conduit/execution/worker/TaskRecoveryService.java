package com.aryan.conduit.execution.worker;

import com.aryan.conduit.execution.queue.StreamMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskRecoveryService {

    private final TaskQueueService taskQueueService;
    private final TaskWorker taskWorker;

    @Value("${conduit.worker.recovery.idle-timeout-ms:30000}")
    private long idleTimeoutMs;

    @Value("${conduit.worker.recovery.batch-size:10}")
    private int batchSize;

    private final String recoveryConsumerName =
            "recovery-" + java.util.UUID.randomUUID();

    @Scheduled(fixedDelay = 10000)
    public void recover() {

        List<StreamMessage> messages =
                taskQueueService.recover(
                        recoveryConsumerName,
                        Duration.ofMillis(
                                idleTimeoutMs
                        ),
                        batchSize
                );

        for (StreamMessage message : messages) {

            taskWorker.process(message);
        }
    }
}
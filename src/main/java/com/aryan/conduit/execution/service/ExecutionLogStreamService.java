package com.aryan.conduit.execution.service;


import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ExecutionLogStreamService {

    private final Map<Long, List<SseEmitter>> emitters =
            new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long executionId) {

        SseEmitter emitter =
                new SseEmitter(0L);

        emitters
                .computeIfAbsent(
                        executionId,
                        id -> new CopyOnWriteArrayList<>()
                )
                .add(emitter);

        emitter.onCompletion(() ->
                emitters.get(executionId).remove(emitter));

        emitter.onTimeout(() ->
                emitters.get(executionId).remove(emitter));

        emitter.onError(e ->
                emitters.get(executionId).remove(emitter));

        return emitter;
    }

    public void send(Long executionId, Object event) {

        List<SseEmitter> clients =
                emitters.get(executionId);

        if (clients == null) {
            return;
        }

        clients.removeIf(emitter -> {

            try {

                emitter.send(event);

                return false;

            } catch (IOException ex) {

                return true;
            }

        });

    }

}
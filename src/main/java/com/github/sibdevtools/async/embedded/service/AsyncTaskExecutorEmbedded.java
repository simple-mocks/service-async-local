package com.github.sibdevtools.async.embedded.service;

import com.github.sibdevtools.async.api.entity.AsyncTask;
import com.github.sibdevtools.async.api.rs.AsyncTaskProcessingResult;
import com.github.sibdevtools.async.api.rs.AsyncTaskProcessingResultBuilder;
import com.github.sibdevtools.async.api.rs.AsyncTaskProcessingRetryResult;
import com.github.sibdevtools.async.api.service.AsyncTaskProcessor;
import com.github.sibdevtools.async.embedded.configuration.properties.AsyncExecutorServiceProperties;
import com.github.sibdevtools.async.embedded.configuration.properties.AsyncServiceEmbeddedProperties;
import com.github.sibdevtools.async.embedded.entity.AsyncTaskEntity;
import com.github.sibdevtools.async.embedded.entity.AsyncTaskParamEntity;
import com.github.sibdevtools.async.embedded.entity.AsyncTaskStatus;
import com.github.sibdevtools.async.embedded.repository.AsyncTaskEntityRepository;
import com.github.sibdevtools.async.embedded.repository.AsyncTaskParamEntityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * @author sibmaks
 * @since 0.0.1
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "service.async.mode", havingValue = "EMBEDDED")
public class AsyncTaskExecutorEmbedded {
    private final AsyncTaskProcessorRegistryEmbedded asyncTaskProcessorRegistry;
    private final AsyncTaskEntityRepository asyncTaskEntityRepository;
    private final AsyncTaskParamEntityRepository asyncTaskParamEntityRepository;
    private final AsyncExecutorServiceProperties properties;
    private final ExecutorService asyncTaskExecutor;

    @Autowired
    public AsyncTaskExecutorEmbedded(AsyncTaskProcessorRegistryEmbedded asyncTaskProcessorRegistry,
                                     AsyncTaskEntityRepository asyncTaskEntityRepository,
                                     AsyncTaskParamEntityRepository asyncTaskParamEntityRepository,
                                     AsyncServiceEmbeddedProperties properties,
                                     @Qualifier("asyncTaskExecutor")
                                     ExecutorService asyncTaskExecutor) {
        this.asyncTaskProcessorRegistry = asyncTaskProcessorRegistry;
        this.asyncTaskEntityRepository = asyncTaskEntityRepository;
        this.asyncTaskParamEntityRepository = asyncTaskParamEntityRepository;
        this.properties = properties.getExecutor();
        this.asyncTaskExecutor = asyncTaskExecutor;
    }

    @Scheduled(fixedRateString = "${service.async.embedded.executor.rate}", scheduler = "asyncScheduledExecutor")
    public void execute() {
        var parallelTasks = properties.getParallelTasks();
        var pageable = Pageable.ofSize(parallelTasks);
        var tasks = asyncTaskEntityRepository.findAllByNextRetryAtBeforeOrderByNextRetryAt(
                ZonedDateTime.now(),
                pageable
        );
        var callables = tasks.stream()
                .map(this::buildCallable)
                .toList();

        try {
            var futures = asyncTaskExecutor.invokeAll(callables);
            for (var future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    log.error("Async task execution exception", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Async task interrupted exception", e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Async task interrupted exception", e);
        }
    }

    private Callable<Void> buildCallable(AsyncTaskEntity asyncTaskEntity) {
        return () -> {
            var type = asyncTaskEntity.getType();
            var version = asyncTaskEntity.getVersion();

            AsyncTaskProcessor processor;
            try {
                processor = asyncTaskProcessorRegistry.getProcessor(type, version);
            } catch (Exception e) {
                log.error("Can't get processor for task type " + type + " and version " + version, e);
                asyncTaskEntity.setStatus(AsyncTaskStatus.FAILED);
                asyncTaskEntity.setLastRetryAt(ZonedDateTime.now());
                asyncTaskEntity.setStatusDescription("Task processor obtain error");
                asyncTaskEntityRepository.save(asyncTaskEntity);
                return null;
            }
            var parameters = asyncTaskParamEntityRepository.findAllByEntityId_Uid(asyncTaskEntity.getUid())
                    .stream()
                    .collect(Collectors.toMap(it -> it.getEntityId().getName(), AsyncTaskParamEntity::getValue));

            var asyncTask = AsyncTask.builder()
                    .uid(asyncTaskEntity.getUid())
                    .type(asyncTaskEntity.getType())
                    .version(asyncTaskEntity.getVersion())
                    .retry(asyncTaskEntity.getRetry())
                    .createdAt(asyncTaskEntity.getCreatedAt())
                    .lastRetryAt(asyncTaskEntity.getLastRetryAt())
                    .parameters(parameters)
                    .build();
            AsyncTaskProcessingResult processingResult;
            try {
                processingResult = processor.process(asyncTask);
            } catch (Exception e) {
                log.error("Async task processing exception. Retry later.", e);
                var defaultRetryStep = processor.getDefaultRetryStep();
                var nextAttempt = ZonedDateTime.now().plus(defaultRetryStep);
                processingResult = AsyncTaskProcessingResultBuilder.createRetryResult(nextAttempt);
            }

            if (processingResult.isFinished()) {
                asyncTaskEntity.setStatus(AsyncTaskStatus.COMPLETED);
                asyncTaskEntity.setLastRetryAt(ZonedDateTime.now());
                asyncTaskEntity.setStatusDescription("Task completed successfully");
            } else {
                var retryProcessingResult = (AsyncTaskProcessingRetryResult) processingResult;
                var nextAttemptAt = retryProcessingResult.getNextAttemptAt();
                asyncTaskEntity.setRetry(asyncTaskEntity.getRetry() + 1);
                asyncTaskEntity.setNextRetryAt(nextAttemptAt);
                asyncTaskEntity.setLastRetryAt(ZonedDateTime.now());
                asyncTaskEntity.setStatus(AsyncTaskStatus.RETRYING);
                asyncTaskEntity.setStatusDescription("Retry is required");
            }
            asyncTaskEntityRepository.save(asyncTaskEntity);

            return null;
        };
    }
}

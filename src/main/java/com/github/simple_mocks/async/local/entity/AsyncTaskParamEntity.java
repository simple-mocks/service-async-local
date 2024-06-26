package com.github.simple_mocks.async.local.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * @author sibmaks
 * @since 0.0.1
 */
@Entity
@Getter
@Setter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "async_task_param")
public class AsyncTaskParamEntity {
    @EmbeddedId
    private AsyncTaskParamEntityId entityId;
    @Column(name = "param_value", nullable = false, length = 1024)
    private String value;
}

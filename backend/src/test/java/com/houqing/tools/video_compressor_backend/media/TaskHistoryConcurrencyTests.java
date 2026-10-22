package com.houqing.tools.video_compressor_backend.media;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaSystemException;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class TaskHistoryConcurrencyTests {
    @Test void sqliteSnapshotConflictWithoutSqlStateReportsRetryableConflict() {
        TaskRepository repository = mock(TaskRepository.class);
        var failure = new JpaSystemException(new RuntimeException(new SQLException(
                "[SQLITE_BUSY_SNAPSHOT] Another connection changed the database", null, 517)));
        when(repository.clearHistory(anyLong(), anyLong())).thenThrow(failure);
        assertThatThrownBy(() -> new TaskHistoryService(repository).clear(1))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCode.INVALID_TASK_STATE);
                    assertThat(error.getMessage()).contains("重试");
                });
    }

    @Test void postgresSerializationFailureReportsConflictButConnectionFailureKeepsDiagnostics() {
        TaskRepository repository = mock(TaskRepository.class);
        var serialization = new JpaSystemException(new RuntimeException(new SQLException("Concurrent update", "40001")));
        var connection = new JpaSystemException(new RuntimeException(new SQLException("Connection failure", "08006")));
        when(repository.clearHistory(anyLong(), anyLong())).thenThrow(serialization).thenThrow(connection);
        var service = new TaskHistoryService(repository);
        assertThatThrownBy(() -> service.clear(1)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo(ErrorCode.INVALID_TASK_STATE));
        assertThatThrownBy(() -> service.clear(1)).isSameAs(connection);
    }
}

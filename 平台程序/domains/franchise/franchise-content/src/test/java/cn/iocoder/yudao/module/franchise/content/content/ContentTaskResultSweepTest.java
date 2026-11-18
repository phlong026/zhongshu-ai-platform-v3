package cn.iocoder.yudao.module.franchise.content.content;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;

class ContentTaskResultSweepTest {
    @Test void restartRecoversSuccessfulTasksEvenWhenGenerationScanningFails() {
        var store=mock(ContentTaskStore.class);
        var tasks=mock(ContentTaskService.class);
        var results=mock(ContentTaskResultService.class);
        when(store.findQueued(20)).thenThrow(new IllegalStateException("queue temporarily unavailable"));
        when(results.pending()).thenReturn(List.of(Map.of("company_id",33L,"id",123L)));
        new ContentTaskJob(store,tasks,results,true).sweep();
        verify(results).deliver(33,123);
        verifyNoInteractions(tasks);
    }

    @Test void aFailedSaveIsDeferredAndDoesNotBlockOtherResultsOrReinvokeGeneration() {
        var store=mock(ContentTaskStore.class);
        var tasks=mock(ContentTaskService.class);
        var results=mock(ContentTaskResultService.class);
        when(results.pending()).thenReturn(List.of(Map.of("company_id",33L,"id",123L),Map.of("company_id",34L,"id",124L)));
        doThrow(new IllegalStateException("save outage")).when(results).deliver(33,123);
        new ContentTaskJob(store,tasks,results,true).sweep();
        verify(results).failed(33,123);
        verify(results).deliver(34,124);
        verifyNoInteractions(tasks);
    }

    @Test void disabledWorkerDoesNotProcessAnyTask() {
        var store=mock(ContentTaskStore.class);
        var tasks=mock(ContentTaskService.class);
        var results=mock(ContentTaskResultService.class);
        new ContentTaskJob(store,tasks,results,false).sweep();
        verifyNoInteractions(store,tasks,results);
    }
}

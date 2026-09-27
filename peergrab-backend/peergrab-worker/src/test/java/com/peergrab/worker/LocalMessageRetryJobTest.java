package com.peergrab.worker;

import com.peergrab.domain.errand.ports.LocalMessageRepository;
import com.peergrab.infrastructure.config.NoopDelayMessageAdapter;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class LocalMessageRetryJobTest {

    @Test
    void disabledMqDoesNotMarkUnsentRowsAsSent() {
        LocalMessageRepository repository = mock(LocalMessageRepository.class);
        new LocalMessageRetryJob(repository, new NoopDelayMessageAdapter()).retry();
        verifyNoInteractions(repository);
    }
}

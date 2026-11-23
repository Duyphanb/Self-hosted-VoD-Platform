package com.vodplatform.auth.service;

import com.vodplatform.auth.persistence.AuthSessionRepository;
import com.vodplatform.auth.persistence.SessionState;
import com.vodplatform.auth.security.AuthStateUnavailableException;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class SessionStateReader {

    private final AuthSessionRepository sessions;

    public SessionStateReader(AuthSessionRepository sessions) {
        this.sessions = sessions;
    }

    public Optional<SessionState> read(UUID id, boolean readAdminRole) {
        try {
            return sessions.readState(id, readAdminRole);
        } catch (DataAccessException | TransactionException | PersistenceException unavailable) {
            throw new AuthStateUnavailableException(unavailable);
        }
    }
}

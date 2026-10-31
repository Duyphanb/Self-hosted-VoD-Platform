package com.vodplatform.encoding.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EncodingJobRepository extends JpaRepository<EncodingJob, UUID> {
}

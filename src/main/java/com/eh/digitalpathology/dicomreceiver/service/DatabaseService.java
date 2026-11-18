package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.entity.TransferRecord;
import com.eh.digitalpathology.dicomreceiver.repository.TransferRecordRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Thin persistence facade over {@link TransferRecordRepository}, kept as its own
 * component to mirror the "Database Update" stage of the ingestion pipeline.
 */
@Service
public class DatabaseService {

    private final TransferRecordRepository repository;

    public DatabaseService(TransferRecordRepository repository) {
        this.repository = repository;
    }

    public TransferRecord save(TransferRecord record) {
        return repository.save(record);
    }

    /** Returns the existing record for a SOP Instance UID (the oldest, if legacy duplicates exist). */
    public Optional<TransferRecord> findBySopInstanceUid(String sopInstanceUid) {
        if (sopInstanceUid == null || sopInstanceUid.isBlank()) {
            return Optional.empty();
        }
        return repository.findBySopInstanceUidOrderByIdAsc(sopInstanceUid).stream().findFirst();
    }

    /** Removes any extra rows sharing the SOP Instance UID, keeping the one with {@code keepId}. */
    public void removeDuplicates(String sopInstanceUid, Long keepId) {
        if (sopInstanceUid == null || sopInstanceUid.isBlank()) {
            return;
        }
        repository.findBySopInstanceUidOrderByIdAsc(sopInstanceUid).stream()
                .filter(r -> !r.getId().equals(keepId))
                .forEach(repository::delete);
    }

    public Optional<TransferRecord> findById(Long id) {
        return repository.findById(id);
    }

    public boolean deleteById(Long id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }

    public List<TransferRecord> findAll() {
        return repository.findAll();
    }

    public List<TransferRecord> search(String query, Sort sort) {
        if (query == null || query.isBlank()) {
            return repository.findAll(sort);
        }
        return repository.findByFileNameContainingIgnoreCaseOrSeriesInstanceUidContainingIgnoreCase(
                query, query, sort);
    }
}

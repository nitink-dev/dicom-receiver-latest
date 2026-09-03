package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.entity.TransferRecord;
import com.eh.digitalpathology.dicomreceiver.repository.TransferRecordRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

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

    public List<TransferRecord> search(String query, Sort sort) {
        if (query == null || query.isBlank()) {
            return repository.findAll(sort);
        }
        return repository.findByFileNameContainingIgnoreCaseOrSeriesInstanceUidContainingIgnoreCase(
                query, query, sort);
    }
}

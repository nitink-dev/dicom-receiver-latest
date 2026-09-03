package com.eh.digitalpathology.dicomreceiver.repository;

import com.eh.digitalpathology.dicomreceiver.entity.TransferRecord;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferRecordRepository extends JpaRepository<TransferRecord, Long> {

    List<TransferRecord> findByFileNameContainingIgnoreCaseOrSeriesInstanceUidContainingIgnoreCase(
            String fileName, String seriesInstanceUid, Sort sort);
}

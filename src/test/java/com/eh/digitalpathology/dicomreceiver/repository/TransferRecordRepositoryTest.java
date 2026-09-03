package com.eh.digitalpathology.dicomreceiver.repository;

import com.eh.digitalpathology.dicomreceiver.entity.TransferRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class TransferRecordRepositoryTest {

    @Autowired
    private TransferRecordRepository repository;

    @Test
    void searchesByFileNameOrSeriesIdAndSortsResults() {
        repository.save(record("SLIDE_001.dcm", "1.2.SER.001", LocalDateTime.now().minusMinutes(5)));
        repository.save(record("SLIDE_002.dcm", "1.2.SER.002", LocalDateTime.now()));
        repository.save(record("OTHER.dcm", "1.2.SER.999", LocalDateTime.now().minusMinutes(10)));

        List<TransferRecord> results = repository.findByFileNameContainingIgnoreCaseOrSeriesInstanceUidContainingIgnoreCase(
                "SLIDE", "SLIDE", Sort.by(Sort.Direction.ASC, "transactionDateTime"));

        assertEquals(2, results.size());
        assertEquals("SLIDE_001.dcm", results.get(0).getFileName());
        assertEquals("SLIDE_002.dcm", results.get(1).getFileName());
    }

    private TransferRecord record(String fileName, String seriesId, LocalDateTime time) {
        TransferRecord record = new TransferRecord();
        record.setFileName(fileName);
        record.setSeriesInstanceUid(seriesId);
        record.setTransactionDateTime(time);
        return record;
    }
}

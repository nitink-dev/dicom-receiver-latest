package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.config.DBRestClient;
import com.eh.digitalpathology.dicomreceiver.exceptions.DbConnectorExeption;

import com.eh.digitalpathology.dicomreceiver.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DatabaseService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class.getName());

    private final DBRestClient dbRestClient;
    @Value("${db.connector.insert.uri}")
    String  uriInsert;

    @Autowired
    public DatabaseService(DBRestClient dbRestClient) {
        this.dbRestClient = dbRestClient;
    }


    public String insertDicomData(DicomRequestDBObject requestDBObject, String sourceServiceName ) throws DbConnectorExeption {
        log.info("insertDicomData :: Started inserting Dicom-Request-Object: {} \nwaiting for db reply..... ..........",requestDBObject);
        HttpHeaders headers = getHttpHeaders(sourceServiceName);
       try {
           return dbRestClient.exchange(HttpMethod.POST, uriInsert, requestDBObject, new ParameterizedTypeReference<ApiResponse<String>>() {
                   }, httpHeaders -> httpHeaders.putAll(headers))
                   .map(ApiResponse::status).block();
       } catch (Exception ex){
           throw new DbConnectorExeption("DB error", ex.getMessage());
       }

    }

    public String saveStorageCommitment(StorageCommitmentRequest request) {
        try {
            return dbRestClient.exchange(
                    HttpMethod.POST,
                    "dicom/storage-commitment",
                    request,
                    new ParameterizedTypeReference<ApiResponse<String>>() {
                    },
                    null
            ).map(ApiResponse::status).block();
        } catch (Exception ex) {
            throw new DbConnectorExeption("DB error", ex.getMessage());
        }
    }

    public Optional<DicomInstance> getDicomInstanceBySopInstanceUid(String sopInstanceUid) {
        try {
            return dbRestClient.exchange(
                    HttpMethod.GET,
                    "dicom/instance/" + sopInstanceUid,
                    null,
                    new ParameterizedTypeReference<ApiResponse<DicomInstance>>() {},
                    null
            ).map(ApiResponse::content).map(Optional::ofNullable).block();
        } catch (Exception ex) {
            log.error("Error fetching DicomInstance for SOPInstanceUID: {}", sopInstanceUid, ex);
            return Optional.empty();
        }
    }

    private static HttpHeaders getHttpHeaders(String serviceName) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Service-Name", serviceName);
        return headers;
    }

    public String saveMetaDataInfo(DicomDirDocument dicomDirDocument) {
        try{
            return dbRestClient.exchange(HttpMethod.POST, "dicom/dicomdir", dicomDirDocument, new ParameterizedTypeReference<ApiResponse<String>>() {
            }, null).map(ApiResponse::status).block();
        } catch (Exception ex){
            throw new DbConnectorExeption("DB error", ex.getMessage());
        }
    }


    public List<StorageCommitmentTracker> getTrackersByRequestId(String reqId) {
        try {
            return dbRestClient.exchange(
                    HttpMethod.GET,
                    "dicom/storage-commitment/trackers/" + reqId,
                    null,
                    new ParameterizedTypeReference<ApiResponse<List<StorageCommitmentTracker>>>() {
                    },
                    null
            ).map(ApiResponse::content).blockOptional().orElse(Collections.emptyList());
        } catch (Exception ex) {
            log.error("Failed to fetch trackers for reqId: {}", reqId, ex);
            return Collections.emptyList();
        }
    }

    public List<String> getPendingStorageCommitmentIds() {
        try {
            return dbRestClient.exchange(
                    HttpMethod.GET,
                    "dicom/storage-commitment/pending-req-ids",
                    null,
                    new ParameterizedTypeReference<ApiResponse<List<String>>>() {
                    },
                    null
            ).map(ApiResponse::content).blockOptional().orElse(Collections.emptyList());
        } catch (Exception ex) {
            log.error("Failed to fetch pending STG_CMT IDs", ex);
            return Collections.emptyList();
        }
    }

    public List<String> getSopInstanceUidsByRequestId(String reqId) {
        return getTrackersByRequestId(reqId).stream()
                .map(StorageCommitmentTracker::getSopInstanceUid)
                .collect(Collectors.toList());
    }

    public void saveStgCmtTracker(StorageCommitmentTracker tracker) {
        dbRestClient.exchange(HttpMethod.POST, "dicom/storage-commitment/tracker", tracker,
                new ParameterizedTypeReference<ApiResponse<String>>() {}, null).block();
    }

    public void updateCmtResponseStatus(String sopUid, boolean status) {
        // You may have to design an endpoint like PATCH or PUT
        Map<String, Object> payload = Map.of("sopInstanceUid", sopUid, "status", status);
        dbRestClient.exchange(HttpMethod.PUT, "dicom/storage-commitment/response-status", payload,
                new ParameterizedTypeReference<ApiResponse<String>>() {}, null).block();
    }

    public void updateStgCmtStatus(SgmtStatus sgmtStatus) {
        dbRestClient.exchange(HttpMethod.PUT, "dicom/storage-commitment/status", sgmtStatus,
                new ParameterizedTypeReference<ApiResponse<String>>() {}, null).block();
    }



    public boolean doesSopExistInDicomInstance(String sopInstanceUid) {
        return getDicomInstanceBySopInstanceUid(sopInstanceUid).isPresent();
    }

    public void saveStgCmtTracker(StorageCommitmentTracker tracker) {
        dbRestClient.exchange(HttpMethod.POST, "dicom/storage-commitment/tracker", tracker,
                new ParameterizedTypeReference<ApiResponse<String>>() {}, null).block();
    }


}
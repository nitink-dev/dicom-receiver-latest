/**
 * DicomHealthcareApiClient class acts as a client for the DICOM Healthcare API, encapsulating
 * the logic for API calls to DICOM stores. It provides a reusable and
 * modular approach for accessing healthcare DICOM data while managing
 * errors through custom exceptions.
 * Copyright (c) 2025 [Your Company Name]. All rights reserved.
 * Author: Pooja Kamble
 * Date: October 30, 2024
 */

package com.eh.digitalpathology.dicomreceiver.service;


import com.eh.digitalpathology.dicomreceiver.api.DicomStreamingEntity;
import com.eh.digitalpathology.dicomreceiver.config.GcpConfig;
import com.eh.digitalpathology.dicomreceiver.exceptions.HealthcareApiException;
import com.eh.digitalpathology.dicomreceiver.util.GCPUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class DicomHealthcareApiService {
    private static final Logger logger = LoggerFactory.getLogger( DicomHealthcareApiService.class );
    private final GcpConfig gcpConfig;
    private final CloseableHttpClient httpClient;

    public DicomHealthcareApiService ( GcpConfig gcpConfig, CloseableHttpClient httpClient ) {
        this.gcpConfig = gcpConfig;
        this.httpClient = httpClient;
    }

    /**
     * This method is used to store dicom instances to final dicom store
     *
     * @param dicomFileMetadata contains file metadata information of dicom instance
     * @param dcmAttributes     contains tag data of dicom instance
     */
    public void storeDicomInstances ( Attributes dicomFileMetadata, Attributes dcmAttributes, String dicomUrl ) throws HealthcareApiException {
        String sopInstanceUid = dcmAttributes.getString( Tag.SOPInstanceUID );
        String accessToken = GCPUtils.getAccessToken( gcpConfig );
        logger.info( "storeDicomInstances :: Starting the process of storing enriched DICOM instance with SOPInstanceUID : {}", sopInstanceUid );
        try {
            uploadInstances( dicomFileMetadata, dcmAttributes, dicomUrl, accessToken, sopInstanceUid );
        } catch ( HealthcareApiException ex ) {
            logger.error( "storeDicomInstances :: Validation failed for DICOM instance with SOPInstanceUID '{}': {}, conflict :: {}", sopInstanceUid, ex.getMessage( ), ex.isConflict( ) );
            if ( ex.isConflict( ) ) {
                logger.error( "storeDicomInstances :: Conflict detected for SOPInstanceUID '{}'. Attempting to delete existing instance and retry upload.", sopInstanceUid );
                deleteExistingInstance( dcmAttributes, dicomUrl, accessToken, sopInstanceUid );
                logger.error( "storeDicomInstances :: Retrying upload for SOPInstanceUID '{}' after successful deletion of existing instance.", sopInstanceUid );
                uploadInstances( dicomFileMetadata, dcmAttributes, dicomUrl, accessToken, sopInstanceUid );
            } else {
                throw ex;
            }
        }

    }

    private void deleteExistingInstance ( Attributes dcmAttributes, String dicomUrl, String accessToken, String sopInstanceUid ) throws HealthcareApiException {
        try {
            String deleteUri = String.format( "%s/%s/dicomWeb/studies/%s/series/%s/instances/%s", gcpConfig.getDicomWebUrl( ), dicomUrl, dcmAttributes.getString( Tag.StudyInstanceUID ), dcmAttributes.getString( Tag.SeriesInstanceUID ), sopInstanceUid );
            logger.info( "deleteExistingInstance :: Constructed DICOM Store URI for deletion: {}", deleteUri );
            HttpDelete httpDelete = new HttpDelete( deleteUri );
            if ( accessToken != null && !accessToken.isEmpty( ) ) {
                httpDelete.setHeader( HttpHeaders.AUTHORIZATION, "Bearer " + accessToken );
            }

            try ( CloseableHttpResponse response = httpClient.execute( httpDelete ) ) {
                int statusCode = response.getStatusLine( ).getStatusCode( );
                if ( statusCode != HttpStatus.SC_OK && statusCode != HttpStatus.SC_NO_CONTENT ) {
                    throw new HealthcareApiException( "Failed deleting SOPInstanceUID=" + sopInstanceUid );
                }
                logger.info( "deleteExistingInstance :: Successfully deleted existing DICOM instance with SOPInstanceUID '{}' from the DICOM store.", sopInstanceUid );
            }
        } catch ( Exception ex ) {
            throw new HealthcareApiException( "Failed deleting SOPInstanceUID=" + sopInstanceUid + "due to :: " + ex.getMessage() );
        }
    }

    private void uploadInstances ( Attributes dicomFileMetadata, Attributes dcmAttributes, String dicomUrl, String accessToken, String sopInstanceUid ) throws HealthcareApiException {

        try {
            String uri = String.format( "%s/%s/dicomWeb/studies", gcpConfig.getDicomWebUrl( ), dicomUrl );
            logger.info( "storeEnrichedDicomInstance :: Constructed DICOM Store URI: {}", uri );
            HttpPost httpPost = new HttpPost( uri );
            httpPost.setHeader( HttpHeaders.CONTENT_TYPE, "application/dicom" );

            if ( accessToken != null && !accessToken.isEmpty( ) ) {
                httpPost.setHeader( HttpHeaders.AUTHORIZATION, "Bearer " + accessToken );
            }
            httpPost.setEntity( new DicomStreamingEntity( dicomFileMetadata, dcmAttributes ) );
            storeInstances( httpPost, sopInstanceUid );
        } catch ( HealthcareApiException ex ) {
            throw ex;
        } catch ( Exception ex ) {
            throw new HealthcareApiException( "Upload failed for SOPInstanceUID=" + sopInstanceUid + " due to :: " + ex.getMessage() );
        }
    }


    private void storeInstances ( HttpPost httpPost, String sopInstanceUid ) throws HealthcareApiException {

        try ( CloseableHttpResponse response = httpClient.execute( httpPost ) ) {
            int statusCode = response.getStatusLine( ).getStatusCode( );
            logger.info( "storeInstances :: statusCode={}", statusCode );
            if ( statusCode == HttpStatus.SC_CONFLICT ) {
                throw HealthcareApiException.conflict( "DICOM instance already exists : " + sopInstanceUid );
            }
            if ( statusCode != HttpStatus.SC_OK ) {
                throw new HealthcareApiException( String.format( "Failed storing SOPInstanceUID=%s status=%s reason=%s", sopInstanceUid, statusCode, response.getStatusLine( ).getReasonPhrase( ) ) );
            }
            logger.info( "storeInstances :: Successfully uploaded SOPInstanceUID={}", sopInstanceUid );
        } catch ( IOException ex ) {
            throw new HealthcareApiException( "Failed during STOW-RS upload for SOPInstanceUID=" + sopInstanceUid + "due to :: " + ex.getMessage() );
        }
    }

}
# DICOM Receiver (Simplified) — Project Documentation

This document describes the **new, simplified DICOM Receiver implementation** as a whole:
what it is for, how it is built, how data flows through it, and how to configure, run, test
and troubleshoot it. It covers only the new implementation.

---

## 1. Purpose

Digital pathology scanners (for example Leica) produce very large DICOM slide images. The
DICOM Receiver:

1. **Receives** those images over the DICOM network protocol.
2. **Reads** their metadata (patient, study, series, SOP instance, scanner).
3. **Stores** the file safely (local disk or Google Cloud Storage).
4. **Records** every file in a database, with **one record per SOP Instance UID**.
5. **Shows** all transfers on a web dashboard, where a record can be searched, sorted or deleted.
6. **Serves** the images back to other systems (query / retrieve) and **confirms** storage
   when asked (storage commitment).

## 2. Technology

| Item | Choice |
|---|---|
| Language / runtime | Java 17, Spring Boot 3.5 |
| DICOM library | dcm4che 5.33 (`dcm4che-core`, `dcm4che-net`) |
| Database | Embedded H2 file database (Spring Data JPA) |
| Storage | Local folder, or Google Cloud Storage bucket |
| UI | Server-rendered Thymeleaf page |
| Build | Maven |

## 3. Architecture

There is **one DICOM network entry point**: the Query/Retrieve SCP (`dcmqrscp`). It also
accepts incoming images, so a separate Store SCP is not needed.

```
 Leica scanner / storeSCU / stgcmtscu / findscu / getscu / movescu
                          |
                  DICOM, port 11112, AE DCMQRSCP
                          |
                    QrScpService
   ┌──────────┬──────────┼───────────┬──────────────┐
 C-STORE   C-ECHO   C-FIND/GET/MOVE   Storage Commitment
   |                    |                  (StorageCommitmentScp)
   v                    v                          |
 data/incoming/     reads database + stored files   v
 <SOP>.dcm                                  checks database
   |
 DirectoryWatcherService (every 5 s)
   |
 FileProcessingService
   |- DicomExtractorService   (read DICOM metadata)
   |- Storage sink            (FileSystemDicomSink | GcsDicomSink)
   '- DatabaseService         (insert, or overwrite on same SOP UID)
   |
 Dashboard  http://localhost:8080  (search, sort, delete)
```

### Components

| Component | Responsibility |
|---|---|
| `DicomReceiverApplication` | Starts Spring; enables scheduling and configuration properties |
| `QrScpService` | The DICOM server: C-STORE, C-ECHO, C-FIND, C-GET, C-MOVE |
| `StorageCommitmentScp` | Storage Commitment Push Model (N-ACTION / N-EVENT-REPORT) |
| `DirectoryWatcherService` | Polls the incoming folder; hands over a file once its size and modified time are unchanged across two polls |
| `FileProcessingService` | Main workflow: extract, de-duplicate, store, save record; invalid files go to `rejected/` |
| `DicomExtractorService` | Reads Patient, Study, Series, SOP and scanner attributes |
| `FileSystemDicomSink` / `GcsDicomSink` | Storage back ends, chosen by `app.storage.type`; both can store, open and delete |
| `DatabaseService` | Persists, finds, searches and deletes `TransferRecord`s |
| `TransferRecord` | One row: file name, series/SOP/study UID, patient, modality, scanner, storage path, timestamp |
| `DashboardController` + `dashboard.html` | Monitoring page and delete action |

## 4. Detailed flows

### 4.1 Receiving an image (C-STORE)
1. The scanner opens an association to `DCMQRSCP` on port 11112. Any SOP class and transfer syntax is accepted.
2. The data is written to `data/incoming/<SOPInstanceUID>.dcm.tmp`.
3. When complete, the file is renamed to `<SOPInstanceUID>.dcm`. The watcher ignores `.tmp`, so it never sees a partial file.
4. The scanner receives Success. On any error the temp file is removed and the scanner receives a failure status.

### 4.2 Processing
1. The watcher finds a stable file and calls `FileProcessingService`.
2. Metadata is extracted. A file that is not valid DICOM is moved to `incoming/rejected/`.
3. The database is searched for the same **SOP Instance UID**.
4. The file is stored (replacing any file with the same name).
5. The record is saved: **same SOP UID = the existing row is overwritten** (same id, new details and timestamp); otherwise a new row is created.
6. If the previous copy was stored under a different name it is deleted, and any leftover duplicate rows are removed.

### 4.3 Dashboard and delete
- `GET /` lists all records, with search (file name or Series ID) and sortable columns.
- Each row has a **Delete** button. A confirmation prompt is shown ("Are you sure you want to delete this record?"). On confirm, `POST /records/{id}/delete` removes the record and returns to the same search/sort view with a "Record deleted." message.
- Delete removes the **database record only**; the stored image file is kept. The record no longer appears in the dashboard or in query/retrieve.

### 4.4 Query / retrieve
| Operation | Behaviour |
|---|---|
| C-ECHO | Connectivity check |
| C-FIND | Searches records at Patient / Study / Series / Image level. Supported keys: Patient ID and name, Study/Series/SOP UID, Modality, Manufacturer, Model, Device Serial Number. `*` and `?` wildcards; other keys are ignored |
| C-GET | Sends matching stored files back on the same association |
| C-MOVE | Sends matching files to another AE listed in `app.qr.move-destinations` (`AET: host:port`); unknown destinations are refused |

Files are sent exactly as stored, with no transcoding.

### 4.5 Storage commitment
1. The SCU sends N-ACTION with a Transaction UID and a list of SOP instances.
2. The receiver replies Success immediately.
3. It checks each SOP instance in the database.
4. It sends N-EVENT-REPORT on the same association: all committed (event 1), or some failed (event 2) with reason `0112H` (no such object instance) for the missing ones.

## 5. Configuration (`application.yml`)

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `app.watch.directory` | `APP_WATCH_DIRECTORY` | `./data/incoming` | Folder watched and written by C-STORE |
| `app.watch.poll-interval-ms` | `APP_WATCH_POLL_INTERVAL_MS` | `5000` | Poll interval |
| `app.qr.enabled` | `APP_QR_ENABLED` | `true` | Enable the DICOM server |
| `app.qr.ae-title` | `APP_QR_AE_TITLE` | `DCMQRSCP` | Server AE title |
| `app.qr.port` | `APP_QR_PORT` | `11112` | DICOM port |
| `app.qr.bind-address` | `APP_QR_BIND_ADDRESS` | `0.0.0.0` | Listen address |
| `app.qr.move-destinations.<AET>` | `APP_QR_MOVE_DESTINATIONS_<AET>` | none | C-MOVE targets |
| `app.storage.type` | `APP_STORAGE_TYPE` | `local` | `local` or `gcs` |
| `app.storage.local-path` | `APP_STORAGE_LOCAL_PATH` | `./data/storage` | Local storage folder |
| `app.storage.gcs-bucket` | `APP_STORAGE_GCS_BUCKET` | empty | GCS bucket name |
| `server.port` | `SERVER_PORT` | `8080` | Web UI port |

The H2 database is stored under `./data/db/`.

## 6. Running

```
mvn spring-boot:run          # local
mvn test                     # tests
docker build -t dicom-receiver-simplified .
docker run -p 8080:8080 -p 11112:11112 -v $(pwd)/data:/data dicom-receiver-simplified
```

## 7. Testing with dcm4che tools

```
# send images
storescu -c DCMQRSCP@127.0.0.1:11112 <folder-or-file>

# storage commitment
stgcmtscu -b STGCMTSCU -c DCMQRSCP@127.0.0.1:11112 <folder-or-file>

# query
findscu -c DCMQRSCP@127.0.0.1:11112 -M StudyRoot -m QueryRetrieveLevel=STUDY -r StudyInstanceUID
```

Open http://localhost:8080/ to see the records, and try the Delete button.

Automated tests (`mvn test`) cover: C-STORE into the watch folder and record creation, duplicate
overwrite, C-FIND, C-GET, storage commitment (success and failure cases), the dashboard Delete
button and the delete action, plus unit tests for processing, extraction and the repository.

## 8. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Scanner cannot connect | Wrong host, port or AE title (`DCMQRSCP`, 11112), or firewall |
| File in `incoming/rejected/` | Not a valid DICOM file |
| Not on dashboard | Wait one or two poll cycles; check logs |
| C-MOVE "unknown destination" | Add the target under `app.qr.move-destinations` |
| Port already in use | Change `app.qr.port` |
| Delete button not visible | It appears on every record row; pull and rebuild the latest `simplified-version` |

## 9. Scope

Included: DICOM C-STORE / C-ECHO / C-FIND / C-GET / C-MOVE / storage commitment, directory
watching, metadata extraction, local and GCS storage, H2 database, dashboard with search, sort and delete.

Not included: Kafka, Redis, SMB/VFS watcher, GCP Healthcare API, barcode tracking, dashboard authentication.

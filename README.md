# DICOM Receiver Service (Simplified)

A lightweight ingestion system for Digital Pathology images. It watches a configured
directory for incoming DICOM files, extracts metadata, stores the image, updates the
database, and shows a transfer report in the UI.

This is a simplified rewrite of the full `dicom-receiver` production service, built to
match the architecture described in `DICOM_Receiver_Service_Final.pdf`. It intentionally
drops everything not in that document — Kafka, Redis, the SMB/VFS2 directory watcher, the
GCP Healthcare API integration, and barcode tracking. A single DICOM server (`dcmqrscp` on port 11112) receives images
and serves query/retrieve and storage commitment. Persistence is a self-contained embedded H2 database instead
of the original external "DB connector" microservice.

## Architecture

```
Scanner / storeSCU -> DCMQRSCP (C-STORE) -> (watch folder) -> Directory Watcher -> File Processing Service -> Metadata Extraction -> Database Update -> Storage (Local / GCS) -> Monitoring Dashboard (UI)
```

- **StorageCommitmentScp** — Storage Commitment Push Model SCP (`1.2.840.10008.1.20.1`),
  registered on the DICOM server (11112). Answers the
  SCU's N-ACTION, then sends an N-EVENT-REPORT on the same association: success for every
  SOP instance we hold a record for, failure (reason 0112H) for unknown ones.
- **QrScpService** — the single DICOM entry point (dcmqrscp equivalent), port 11112, AE
  title `DCMQRSCP`. Receives images (C-STORE, written into the watch folder), and serves C-ECHO, C-FIND, C-GET and C-MOVE (Patient Root / Study Root) from the
  database records and stored files. C-MOVE destinations are configured under
  `app.qr.move-destinations` (AE title -> `host:port`). Files are streamed as stored
  (no transcoding).
- **DirectoryWatcherService** — polls a single configured folder for new files, only
  handing a file off once its size and modification time are stable across two polls.
- **FileProcessingService** — orchestrates: extract metadata, store the file, persist a
  transfer record. Files that fail extraction are moved into a `rejected/` subfolder.
- **DicomExtractorService** — reads Patient, Study, Series and Scanner attributes from
  the DICOM file using dcm4che.
- **FileSystemDicomSink** / **GcsDicomSink** — mutually exclusive storage backends,
  selected by `app.storage.type` (`local` or `gcs`).
- **Duplicates** — a file whose SOP Instance UID already has a record overwrites that
  record (same row, new metadata/timestamp) and replaces the stored file; no new row.
- **DatabaseService** — persists/searches `TransferRecord`s via Spring Data JPA + H2.
- **DashboardController** — single reporting screen (`/`) listing all transfers, with
  search (file name or series ID), sortable columns, and a Delete button per row (with a
  confirmation prompt; removes the record only, the stored file is kept).

For a complete project documentation (architecture, flows, config, testing) see [docs/PROJECT_DOCUMENTATION.md](docs/PROJECT_DOCUMENTATION.md).

## Running locally

```
mvn spring-boot:run
```

The DICOM server (`DCMQRSCP`) listens on port 11112. Send a file with, e.g.:

```
storescu -c DCMQRSCP@localhost:11112 file.dcm
```

Alternatively drop a `.dcm` file into `./data/incoming` — within one poll interval (default 5s)
it will be moved into `./data/storage` and appear as a row at http://localhost:8080/.

## Configuration

All settings live in `src/main/resources/application.yml` and can be overridden with
environment variables (Spring relaxed binding), e.g.:

| Property | Env var | Default |
|---|---|---|
| `app.watch.directory` | `APP_WATCH_DIRECTORY` | `./data/incoming` |
| `app.watch.poll-interval-ms` | `APP_WATCH_POLL_INTERVAL_MS` | `5000` |
| `app.qr.enabled` | `APP_QR_ENABLED` | `true` |
| `app.qr.ae-title` | `APP_QR_AE_TITLE` | `DCMQRSCP` |
| `app.qr.port` | `APP_QR_PORT` | `11112` |
| `app.qr.bind-address` | `APP_QR_BIND_ADDRESS` | `0.0.0.0` |
| `app.qr.move-destinations.<AET>` | `APP_QR_MOVE_DESTINATIONS_<AET>` | (none) |
| `app.storage.type` | `APP_STORAGE_TYPE` | `local` |
| `app.storage.local-path` | `APP_STORAGE_LOCAL_PATH` | `./data/storage` |
| `app.storage.gcs-bucket` | `APP_STORAGE_GCS_BUCKET` | (empty) |

The H2 database file lives under `./data/db/`.

## Tests

```
mvn test
```

## Docker

```
docker build -t dicom-receiver-simplified .
docker run -p 8080:8080 -p 11112:11112 -v $(pwd)/data:/data dicom-receiver-simplified
```

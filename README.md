# DICOM Receiver Service (Simplified)

A lightweight ingestion system for Digital Pathology images. It watches a configured
directory for incoming DICOM files, extracts metadata, stores the image, updates the
database, and shows a transfer report in the UI.

This is a simplified rewrite of the full `dicom-receiver` production service, built to
match the architecture described in `DICOM_Receiver_Service_Final.pdf`. It intentionally
drops everything not in that document — Kafka, Redis, the SMB/VFS2 directory watcher, the
GCP Healthcare API integration, the dcm4che STORE-SCP network listener, and barcode /
storage-commitment tracking. Persistence is a self-contained embedded H2 database instead
of the original external "DB connector" microservice.

## Architecture

```
Scanner / storeSCU -> Store SCP -> (watch folder) -> Directory Watcher -> File Processing Service -> Metadata Extraction -> Database Update -> Storage (Local / GCS) -> Monitoring Dashboard (UI)
```

- **StoreScpService** — DICOM Store SCP started on application load. Receives C-STORE
  from a Leica scanner or any storeSCU client (also answers C-ECHO) and writes files into
  the watch folder as `.tmp`, renaming to `.dcm` when complete so the watcher never sees
  a partial file. All SOP classes / transfer syntaxes are accepted.
- **QrScpService** — Query/Retrieve SCP (dcmqrscp equivalent) on port 11113, AE title
  `DCMQRSCP`. Serves C-ECHO, C-FIND, C-GET and C-MOVE (Patient Root / Study Root) from the
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

For a plain-language walkthrough of the whole flow see [docs/CODE_FLOW.md](docs/CODE_FLOW.md).

## Running locally

```
mvn spring-boot:run
```

The Store SCP listens on port 11112 (AE title `DICOMRECV`). Send a file with, e.g.:

```
storescu -c DICOMRECV@localhost:11112 file.dcm
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
| `app.scp.enabled` | `APP_SCP_ENABLED` | `true` |
| `app.scp.ae-title` | `APP_SCP_AE_TITLE` | `DICOMRECV` |
| `app.scp.port` | `APP_SCP_PORT` | `11112` |
| `app.scp.bind-address` | `APP_SCP_BIND_ADDRESS` | `0.0.0.0` |
| `app.qr.enabled` | `APP_QR_ENABLED` | `true` |
| `app.qr.ae-title` | `APP_QR_AE_TITLE` | `DCMQRSCP` |
| `app.qr.port` | `APP_QR_PORT` | `11113` |
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
docker run -p 8080:8080 -p 11112:11112 -p 11113:11113 -v $(pwd)/data:/data dicom-receiver-simplified
```

# How the DICOM Receiver Works — Code Flow in Simple Words

This guide explains, step by step, what happens inside the application from the moment a
scanner sends an image until you see it on the dashboard or fetch it back later.
No DICOM expertise needed.

## 1. The big picture

Think of the application as a small **post office for slide images**:

| Post office part | In the code | What it does |
|---|---|---|
| Front counter (receiving) | `StoreScpService` | Accepts images sent by the Leica scanner or any storeSCU tool |
| Sorting tray (inbox folder) | `./data/incoming` | A folder where new files are placed first |
| Clerk who watches the tray | `DirectoryWatcherService` | Checks the folder every 5 seconds for new files |
| Inspector | `FileProcessingService` + `DicomExtractorService` | Reads each file's label (patient, study, series, SOP ID) |
| Warehouse | `FileSystemDicomSink` (or `GcsDicomSink`) | Stores the file safely (local disk or Google Cloud) |
| Ledger book | `DatabaseService` (H2 database) | One row per image in the `TransferRecord` table |
| Notice board | `DashboardController` + `dashboard.html` | Web page that lists all rows, with search and Delete |
| Retrieval desk | `QrScpService` | Lets other systems search for and fetch stored images (dcmqrscp) |

```
 Scanner / storeSCU
        |  (C-STORE, port 11112)
        v
  StoreScpService ──writes──> data/incoming/<SOP>.dcm
                                      |
                       DirectoryWatcherService (every 5s)
                                      |
                       FileProcessingService
                         |- DicomExtractorService  (read the label)
                         |- Storage sink           (move file to storage)
                         '- DatabaseService        (save / overwrite row)
                                      |
                    ┌─────────────────┴──────────────────┐
                    v                                    v
        Dashboard  http://localhost:8080        QrScpService (port 11113)
        (search, sort, delete)                  other systems search & fetch
```

## 2. Starting up

When the application starts (`DicomReceiverApplication`), Spring creates all the services.
Two of them open network doors straight away:

- **StoreScpService** listens on port **11112**, AE title **DICOMRECV**.
- **QrScpService** listens on port **11113**, AE title **DCMQRSCP**.

The watcher also starts its 5-second timer. All settings (ports, folders, AE titles) come
from `application.yml` and can be changed there or with environment variables.

## 3. Receiving an image (StoreScpService)

1. A scanner or `storescu` connects and says "I want to store an image".
2. The service agrees to **any** image type, so no scanner gets turned away.
3. As bytes arrive, they are written to `data/incoming/<SOPInstanceUID>.dcm.tmp`.
4. When the last byte arrives, the file is **renamed** to `<SOPInstanceUID>.dcm`.
5. The sender gets "Success". If anything fails, the half-written file is deleted and the
   sender is told it failed.

**Why `.tmp` first?** The watcher ignores `.tmp` files. This guarantees it never picks up a
file that is still being written.

## 4. Picking up the file (DirectoryWatcherService)

Every 5 seconds the watcher lists the `incoming` folder. For each file it remembers the
size and last-modified time. Only when **two checks in a row show the same values** does it
decide "this file is finished" and hand it over. This is the same safety check that also
covers files copied into the folder by hand.

## 5. Processing (FileProcessingService)

For each finished file, in order:

1. **Read the label** — `DicomExtractorService` pulls out Patient ID/name, Study, Series,
   SOP Instance ID, modality and scanner details. If the file is not valid DICOM, it is
   moved to `incoming/rejected/` and nothing else happens.
2. **Check for a duplicate** — look in the database for a row with the same
   **SOP Instance ID**.
3. **Store the file** — the storage sink moves it into `data/storage` (or a Google Cloud
   bucket). An older file with the same name is replaced.
4. **Save the row** — if step 2 found an existing row, its `id` is reused, so the row is
   **overwritten** (new details, new timestamp) instead of a new one being added. Otherwise a new row
   is created.
5. **Tidy up** — if the duplicate had been stored under a different file name, the old file
   is deleted, and any extra leftover rows with the same SOP ID are removed.

> **Rule:** one SOP Instance ID = one record, always.

## 6. The dashboard (DashboardController)

Open `http://localhost:8080/`.

- Shows every record: file name, Series ID, SOP ID, date and time.
- **Search** by file name or Series ID; **click a column** to sort.
- **Delete** button on each row: a pop-up asks "Are you sure?". If you confirm, the page sends
  a POST to `/records/{id}/delete`, the row is removed, and you return to the same
  search/sort view with a "Record deleted." message.
- Delete removes **only the database record**. The stored image file stays on disk/cloud.
  But because the retrieval desk works from the database, a deleted record can no longer be
  found or fetched by Query/Retrieve.

## 7. Searching and fetching (QrScpService — dcmqrscp)

Other systems connect to port **11113**, AE title **DCMQRSCP**, and can:

| Request | What happens |
|---|---|
| **C-ECHO** | "Are you alive?" — answers yes |
| **C-FIND** | Searches database rows (by patient, study, series, SOP ID, modality, etc.; `*` and `?` wildcards work) and returns matching entries at Patient / Study / Series / Image level |
| **C-GET** | Finds matching rows, opens each stored file and sends it back on the **same connection** |
| **C-MOVE** | Same as C-GET, but the images are sent to **another** AE. That AE must be listed in `app.qr.move-destinations` (name → `host:port`), otherwise the request is refused as "unknown destination" |

Files are sent exactly as stored (no format conversion).

## 8. Which file does what

```
src/main/java/.../dicomreceiver/
  DicomReceiverApplication.java   starts everything
  config/    WatcherProperties, StorageProperties,
             ScpProperties (store SCP), QrScpProperties (query/retrieve)
  service/   StoreScpService        receives images from the network
             DirectoryWatcherService watches the incoming folder
             FileProcessingService  main workflow + duplicate handling
             DicomExtractorService  reads DICOM labels
             FileSystemDicomSink / GcsDicomSink   storage (pick one in config)
             DatabaseService        saves, finds, deletes records
             QrScpService           C-FIND / C-GET / C-MOVE
  api/       DicomObjectSink        the "storage" contract both sinks follow
  entity/    TransferRecord         one database row
  repository/ TransferRecordRepository
  web/       DashboardController    dashboard + delete
resources/   application.yml, templates/dashboard.html
```

## 9. Quick test recipe

```
# 1. start the app
mvn spring-boot:run

# 2. send an image (dcm4che tools)
storescu -c DICOMRECV@localhost:11112 myimage.dcm

# 3. open the dashboard
open http://localhost:8080/

# 4. search it
findscu -c DCMQRSCP@localhost:11113 -m QueryRetrieveLevel=STUDY -M StudyRoot -r StudyInstanceUID

# 5. send the same file again -> still ONE row, updated time
```

## 10. If something goes wrong

| Symptom | Likely cause |
|---|---|
| Scanner cannot connect | Wrong host/port/AE title, or firewall blocks 11112 |
| File ends up in `incoming/rejected/` | It is not a valid DICOM file |
| Image not on the dashboard | Wait one or two poll cycles (5–10 s); check the logs |
| C-MOVE says unknown destination | Add the target to `app.qr.move-destinations` |
| Port already in use | Change `app.scp.port` / `app.qr.port` |

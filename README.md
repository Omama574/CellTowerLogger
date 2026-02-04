# CellTowerLogger v2.0

An event-driven Android utility designed for high-resolution cellular network analysis and GPS performance auditing. This version pivots away from inefficient polling to a reactive architecture.

## Technical Architecture
- **Event-Driven Telephony**: Utilizes TelephonyCallback to trigger data collection only upon a physical cell handover, significantly reducing radio modem wake-up cycles.
- **GPS Performance Audit**: Executes a high-accuracy location request every 5 minutes to measure "Time-to-First-Fix" (Latency).
- **Audit Logic**: Captures precise timestamps for request initiation and response reception to benchmark Fused Location Provider (FLP) efficiency in transit environments.
- **State Persistence**: Implements BufferedWriter.flush() for real-time I/O safety and a Foreground Service for process priority.

## Data Schema (CSV)
| Column | Description |
| :--- | :--- |
| **Timestamp** | RFC 3339 formatted event time |
| **Event_Type** | CELL_CHANGE, LOCATION_SUCCESS, or LOCATION_FAILURE |
| **CID / TAC** | Primary Serving Cell identifiers |
| **Signal_dBm** | Signal strength at time of event |
| **Lat / Lon** | WGS84 Coordinates |
| **Accuracy** | Horizontal accuracy radius in meters |
| **Latency_ms** | Milliseconds between GPS request and fix |

## Field Test Requirements
- **Battery**: Set to "Unrestricted" to bypass Doze mode during the 5-minute GPS audit.
- **Permissions**: Location must be set to "Allow all the time."

# CellTowerLogger v3.1 - "Stealth Audit"

This version evaluates the performance of **Network-Based Location** (Wi-Fi/Cell Triangulation) vs. **Pure GPS**. It is optimized for long-duration transit logging with minimal battery impact.

## Key Changes in v3.1
- **Priority Shift**: Migrated from PRIORITY_HIGH_ACCURACY to PRIORITY_BALANCED_POWER_ACCURACY.
- **Daisy-Chain Scheduling**: Replaced fixed timers with sequential scheduling (scheduleNextAudit()). A new 5-minute cycle only starts *after* the previous one concludes or times out.
- **Extended Persistence**: The app now waits the full 5-minute interval for a balanced fix to resolve, testing the limits of non-GPS positioning on moving trains.
- **Improved I/O**: Real-time BufferedWriter flushing for data integrity.

## Technical Specifications
- **Location Mode**: Balanced Power (Cell + Wi-Fi)
- **Audit Interval**: 5 Minutes (Sequential)
- **Cell Logging**: Event-driven (triggered by onCellInfoChanged)
- **Min API**: 24 (Android 7.0)
- **Target API**: 34 (Android 14.0)

## CSV Schema Updates
- Event_Type: Now logs BALANCED_FIX for successful location audits.
- Latency_ms: Time taken for the network location to resolve within the 5-minute window.

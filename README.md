# CellTowerLogger

An Android utility designed for high-resolution cellular network data collection. This application tracks serving and neighboring cell tower metadata in a background environment, persisting data to local storage for forensic analysis.

## Core Functionality
- **Background Data Collection**: Utilizes a Foreground Service to maintain data polling even when the device is locked or the application is not in the active stack.
- **Telephony Polling**: Queries the TelephonyManager API at one-minute intervals for Cell ID (CID), Tracking Area Code (TAC), Signal Strength (dBm), and MCC/MNC identifiers.
- **Intelligent De-duplication**: Implements change-based logging logic to prevent redundant entries; new rows are appended to the dataset only upon a physical handover or after a 10-minute heartbeat threshold.
- **Export Capabilities**: Local data is persisted as a CSV (tower_logs.csv) and exposed via FileProvider for secure external sharing.

## Technical Specifications
- **Minimum SDK**: API 24 (Android 7.0)
- **Target SDK**: API 34
- **Language**: Kotlin

## Installation and Permissions
To ensure reliable operation, the following permissions must be granted:
- ACCESS_FINE_LOCATION (Always)
- READ_PHONE_STATE
- ACCESS_BACKGROUND_LOCATION
- POST_NOTIFICATIONS

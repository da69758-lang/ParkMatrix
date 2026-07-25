# ParkMatrix

**ParkMatrix** is a 4-floor automated parking management system built as a single-file JavaFX desktop application. It simulates a real multi-level parking facility — vehicles are scanned in, assigned a slot automatically, and issued a printed slip or membership card, all through a red-and-white ticket-styled interface.

---

## Features

### 4-Floor Facility (100 slots per floor)

| Floor | Level        | Assigned To                  | Rate                                   |
|-------|--------------|-------------------------------|-----------------------------------------|
| **A** | Ground Floor | Heavy Vehicles                 | Rs 50 every hour (no free hour)         |
| **B** | 1st Floor    | Members Reserved (Cars/Bikes)  | Monthly / Weekly membership             |
| **C** | 2nd Floor    | Bikes                          | 1st hour free, then Rs 20/hr            |
| **D** | 3rd Floor    | Cars                           | 1st hour free, then Rs 30/hr            |

### New Vehicle Entry
- Vehicle type selector (Car / Bike / Heavy Vehicle)
- Simulated camera-scan animation before the slip is issued
- Automatic floor routing based on vehicle type
- Slots are assigned **randomly** and are **never double-allocated**
- Duplicate entries (a reg. no. already parked) are blocked automatically
- Recognizes paid members automatically — no duplicate slip is issued for them
- Prints a vertical **parking slip** with vehicle type, reg. no., assigned slot, and time of parking

### Members (Floor B)
- Register a member's car or bike
- Choose **Monthly** (pick a month) or **Weekly** (pick a week start date) timeframe
- Mark payment status as **Paid** / **Unpaid**
- Issues a vertical **member card** with vehicle type, reg. no., allotted slot, timeframe, and status
- Members list supports toggling status, reprinting the card, or ending a membership (frees the slot)

### Checkout
- Live list of every currently parked non-member vehicle
- Real-time fee calculation based on elapsed time and vehicle-type rate
- Search by registration number
- One-click checkout that frees the slot and shows the final fare

### Dashboard
- Occupancy bar and live count for each of the 4 floors
- Facility-wide live summary: active members, unpaid members, non-member vehicles parked, free slots

### Design
- Red-and-white theme throughout
- Slips and member cards are vertical, with a dashed **stitched-border** effect, perforation-dot rows, and a printed barcode strip — built to look like something a real parking machine would print

---

## Requirements

- **JDK 17+** (developed and tested on JDK 21)
- **JavaFX SDK** matching your JDK (JavaFX is no longer bundled with the JDK)

On Debian/Ubuntu, the simplest way to get JavaFX is:

```bash
sudo apt install openjfx
```

This installs the JavaFX libraries to `/usr/share/openjfx/lib`.

Alternatively, download the JavaFX SDK for your platform from [gluonhq.com/products/javafx](https://gluonhq.com/products/javafx/) and note the path to its `lib` folder.

---

## How to Run

From the folder containing `ParkingManagementSystem.java`:

**Compile:**
```bash
javac --module-path /usr/share/openjfx/lib --add-modules javafx.controls ParkingManagementSystem.java
```

**Run:**
```bash
java --module-path /usr/share/openjfx/lib --add-modules javafx.controls ParkingManagementSystem
```

> Replace `/usr/share/openjfx/lib` with the actual path to your JavaFX SDK's `lib` folder if you installed it manually.

### Running from an IDE (IntelliJ IDEA / Eclipse / VS Code)

1. Add the JavaFX SDK as a library to your project.
2. Add VM options:
   ```
   --module-path /path/to/javafx-sdk/lib --add-modules javafx.controls
   ```
3. Run `ParkingManagementSystem.java` directly — it contains the `main` method.

---

## Project Structure

The entire application lives in a single file for portability:

```
ParkingManagementSystem.java   — all UI, logic, and data model
```

Everything is in-memory — data resets when the app is closed. Use the **Reset** button in the navbar to clear all vehicles and members at any time during a session.

---

## Notes

- Testing fee calculation doesn't require waiting for real hours to pass — the **New Entry** form has an optional "Entry Time" field so a past date/time can be entered to simulate a longer stay.
- This is a demo / prototype system — there is no database or file persistence layer; all vehicle, slot, and member data is held in memory for the duration of the running session.

# Phemic

Phemic is a wireless peripheral bridge connecting an Android device to a Linux host over a low-latency UDP protocol. It allows an Android phone or tablet to function as:

- A wireless microphone for Linux (PulseAudio / PipeWire)
- A wireless speaker receiver for Linux system audio
- A multi-touch trackpad and pressure-sensitive drawing tablet (via Linux uinput)

## System Architecture

Phemic consists of two main components:

1. **Host Daemon (`server/`)**: A Go service running on Linux that interfaces directly with `/dev/uinput` for input events and PulseAudio/PipeWire for virtual audio routing.
2. **Android Client (`android/`)**: A modern Jetpack Compose application running a foreground service with Quick Settings tile integration and auto-discovery.

### Communication Protocol

- Transport: UDP on port 9898
- Discovery: Broadcast packets (`0x01` Discover, `0x02` Heartbeat) for automatic local network pairing
- Audio Format: Signed 16-bit PCM little-endian at 48000 Hz
  - Microphone: 1-channel mono, routed via a named FIFO pipe into PulseAudio `module-pipe-source` (`Phemic_Virtual_Mic`)
  - Speaker: 2-channel stereo, captured via PulseAudio `module-null-sink` and `parec` (`Phemic_Virtual_Speaker`)
- Input Events: Multi-touch and absolute coordinate digitizer packets dispatched to the kernel via `/dev/uinput`

## Features

### Audio
- **Virtual Microphone**: Streams audio recorded on the Android device to a virtual Linux microphone source. Compatible with Discord, Zoom, OBS, and any Linux audio app.
- **Virtual Speaker**: Captures Linux system audio routed to `Phemic_Virtual_Speaker` and streams it back to the Android device with low latency.
- **Hardware Integration**: Supports hardware volume key shortcuts for muting and unmuting.

### Input Modes
- **Trackpad (Relative Mode)**: Standard laptop-style trackpad with tap-to-click, multi-finger gestures, and mouse button controls.
- **Graphics Tablet (Absolute Mode)**: Maps the Android screen to the monitor coordinates with support for active stylus pens, touch pressure curves, and threshold adjustments.
- **Display Controls**: Toggle full-screen mode, landscape orientation, and on-screen toolbar auto-hide.

### Android Application
- Modern Material 3 UI built with Jetpack Compose.
- Foreground service with notification controls to prevent background killing.
- Quick Settings tile support for fast toggling.
- Automatic LAN discovery of active Linux hosts.

## Prerequisites

### Linux Host
- Linux kernel with `CONFIG_INPUT_UINPUT` enabled (standard on most distributions).
- Read/write access to `/dev/uinput`.
- PulseAudio or PipeWire with `pulseaudio-utils` (`pactl` and `parec` binaries).
- Go 1.20 or later (for compiling from source).

### Android Device
- Android 8.0 (API 26) or higher.
- Device connected to the same local network (Wi-Fi or USB tethering) as the Linux host.

## Installation and Setup

### 1. Configure Linux Host Permissions

Access to `/dev/uinput` requires appropriate permissions. You can configure a udev rule to allow access without running the server as root:

Create `/etc/udev/rules.d/99-uinput.rules`:

```udev
KERNEL=="uinput", MODE="0660", GROUP="input", TAG+="uaccess"
```

Add your user to the `input` group:

```bash
sudo usermod -aG input $USER
```

Reload udev rules or reboot:

```bash
sudo udevadm control --reload-rules && sudo udevadm trigger
```

Ensure `uinput` module is loaded:

```bash
sudo modprobe uinput
```

### 2. Build and Run the Server

```bash
cd server
go build -o phemic-server .
./phemic-server
```

To enable verbose libinput-style event debugging:

```bash
./phemic-server -debug
```

The server displays available local IP addresses and begins listening on UDP port 9898.

### 3. Build and Install the Android App

Using Android Studio, open the `android` directory, or build using Gradle:

```bash
cd android
./gradlew assembleDebug
```

Install the APK directly onto a connected device via ADB:

```bash
./gradlew installDebug
```

## Usage

1. Start the server on your Linux machine:
   ```bash
   ./server/phemic-server
   ```
2. Open the Phemic app on Android.
3. The app scans the local network and discovers available servers. Select your host IP or enter it manually, then tap **Connect**.
4. Enable desired modules:
   - **Mic**: Routes phone microphone to `Phemic_Virtual_Mic`.
   - **Speaker**: Routes PC audio sent to `Phemic_Virtual_Speaker` to phone speakers or headphones.
   - **Touchpad**: Switch to the Touchpad view for mouse control or stylus drawing.

### Audio Routing on Linux

Once the server connects, select the virtual devices in your Linux sound settings or `pavucontrol`:
- Input device: `Phemic_Virtual_Mic`
- Output device: `Phemic_Virtual_Speaker` (when using speaker streaming)

## Network and Firewall Configuration

If connection fails, verify that UDP port 9898 is allowed on your firewall:

For `ufw`:
```bash
sudo ufw allow 9898/udp
```

For `firewalld`:
```bash
sudo firewall-cmd --add-port=9898/udp --permanent
sudo firewall-cmd --reload
```

## Project Structure

```text
phemic/
├── android/
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       └── java/com/phemic/app/
│   │           ├── MainActivity.kt        # Compose UI and state handling
│   │           ├── PhemicService.kt       # Foreground service, UDP network, audio
│   │           ├── PhemicTileService.kt   # Quick Settings tile integration
│   │           ├── PhemicTileActivity.kt  # Quick Settings trampoline activity
│   │           └── TouchpadScreen.kt      # Trackpad & tablet input canvas
│   ├── build.gradle.kts
│   ├── gradle/
│   ├── gradlew
│   └── settings.gradle.kts
├── server/
│   ├── audio.go                           # PulseAudio virtual sink/source setup
│   ├── go.mod
│   ├── go.sum
│   ├── main.go                            # CLI entry point and signal handling
│   ├── network.go                         # UDP packet dispatcher and keepalive
│   └── uinput.go                          # Linux uinput virtual device driver
└── .gitignore
```

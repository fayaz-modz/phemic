package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"os"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

const (
	UI_SET_EVBIT   = 0x40045564 // _IOW('U', 100, 4)
	UI_SET_KEYBIT  = 0x40045565 // _IOW('U', 101, 4)
	UI_SET_RELBIT  = 0x40045566 // _IOW('U', 102, 4)
	UI_SET_ABSBIT  = 0x40045567 // _IOW('U', 103, 4)
	UI_SET_PROPBIT = 0x4004556e // _IOW('U', 110, 4)
	UI_DEV_CREATE  = 0x5501     // _IO('U', 1)
	UI_DEV_DESTROY = 0x5502     // _IO('U', 2)
	UI_DEV_SETUP   = 0x405c5503 // _IOW('U', 3, 92)
	UI_ABS_SETUP   = 0x401c5504 // _IOW('U', 4, 28)

	INPUT_PROP_POINTER   = 0x00
	INPUT_PROP_DIRECT    = 0x01
	INPUT_PROP_BUTTONPAD = 0x02

	EV_SYN = 0
	EV_KEY = 1
	EV_REL = 2
	EV_ABS = 3

	SYN_REPORT = 0

	BTN_LEFT           = 272
	BTN_RIGHT          = 273
	BTN_MIDDLE         = 274
	BTN_TOOL_PEN       = 320
	BTN_TOOL_FINGER    = 325
	BTN_TOOL_DOUBLETAP = 326
	BTN_TOOL_TRIPLETAP = 327
	BTN_TOOL_QUADTAP   = 328
	BTN_TOOL_QUINTTAP  = 329
	BTN_TOUCH          = 330
	BTN_STYLUS         = 331
	BTN_STYLUS2        = 332

	REL_X      = 0
	REL_Y      = 1
	REL_HWHEEL = 6
	REL_WHEEL  = 8

	ABS_X              = 0
	ABS_Y              = 1
	ABS_PRESSURE       = 24
	ABS_MT_SLOT        = 47
	ABS_MT_POSITION_X  = 53
	ABS_MT_POSITION_Y  = 54
	ABS_MT_TRACKING_ID = 57
	ABS_MT_PRESSURE    = 58
)

type inputAbsInfo struct {
	Value      int32
	Minimum    int32
	Maximum    int32
	Fuzz       int32
	Flat       int32
	Resolution int32
}

type uinputAbsSetup struct {
	Code    uint16
	_       uint16 // padding
	AbsInfo inputAbsInfo
}

type inputID struct {
	Bustype uint16
	Vendor  uint16
	Product uint16
	Version uint16
}

type uinputSetup struct {
	ID           inputID
	Name         [80]byte
	FFEffectsMax uint32
}

type inputEvent struct {
	Time  syscall.Timeval
	Type  uint16
	Code  uint16
	Value int32
}

type InputSystem struct {
	penFile        *os.File
	touchpadFile   *os.File
	mu             sync.Mutex
	debug          bool
	nextTrackingID int32
	slotTracking   [10]int32
}

func setIoctl(fd uintptr, req uintptr, value uintptr) error {
	_, _, err := syscall.Syscall(syscall.SYS_IOCTL, fd, req, value)
	if err != 0 {
		return err
	}
	return nil
}

func setupAbsAxis(fd uintptr, code uint16, minVal, maxVal, resVal int32) error {
	abs := uinputAbsSetup{Code: code}
	abs.AbsInfo.Minimum = minVal
	abs.AbsInfo.Maximum = maxVal
	abs.AbsInfo.Resolution = resVal
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, UI_ABS_SETUP, uintptr(unsafe.Pointer(&abs)))
	if errno != 0 {
		return fmt.Errorf("UI_ABS_SETUP code=%d failed: %v", code, errno)
	}
	return nil
}

func createDevSetup(fd uintptr, name string, bustype, vendor, product uint16) error {
	var setup uinputSetup
	copy(setup.Name[:], name)
	setup.ID = inputID{Bustype: bustype, Vendor: vendor, Product: product, Version: 1}
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, UI_DEV_SETUP, uintptr(unsafe.Pointer(&setup)))
	if errno != 0 {
		return fmt.Errorf("UI_DEV_SETUP failed: %v", errno)
	}
	return nil
}

func createPenDevice() (*os.File, error) {
	file, err := os.OpenFile("/dev/uinput", os.O_WRONLY|syscall.O_NONBLOCK, 0600)
	if err != nil {
		return nil, fmt.Errorf("failed to open /dev/uinput for pen: %v", err)
	}
	fd := file.Fd()

	for _, ev := range []uintptr{EV_KEY, EV_ABS} {
		if err := setIoctl(fd, UI_SET_EVBIT, ev); err != nil {
			file.Close()
			return nil, err
		}
	}

	if err := setIoctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT); err != nil {
		file.Close()
		return nil, fmt.Errorf("UI_SET_PROPBIT failed: %v", err)
	}

	for _, key := range []uintptr{BTN_TOOL_PEN, BTN_TOUCH, BTN_STYLUS, BTN_STYLUS2} {
		_ = setIoctl(fd, UI_SET_KEYBIT, key)
	}
	for _, abs := range []uintptr{ABS_X, ABS_Y, ABS_PRESSURE} {
		_ = setIoctl(fd, UI_SET_ABSBIT, abs)
	}

	_ = setupAbsAxis(fd, ABS_X, 0, 65535, 100)
	_ = setupAbsAxis(fd, ABS_Y, 0, 65535, 100)
	_ = setupAbsAxis(fd, ABS_PRESSURE, 0, 4095, 0)

	if err := createDevSetup(fd, "Phemic Virtual Pen", 0x03, 0x056a, 0x00d1); err != nil {
		file.Close()
		return nil, err
	}

	if err := setIoctl(fd, UI_DEV_CREATE, 0); err != nil {
		file.Close()
		return nil, fmt.Errorf("UI_DEV_CREATE pen failed: %v", err)
	}
	return file, nil
}

func createTouchpadDevice() (*os.File, error) {
	file, err := os.OpenFile("/dev/uinput", os.O_WRONLY|syscall.O_NONBLOCK, 0600)
	if err != nil {
		return nil, fmt.Errorf("failed to open /dev/uinput for touchpad: %v", err)
	}
	fd := file.Fd()

	for _, ev := range []uintptr{EV_KEY, EV_ABS} {
		if err := setIoctl(fd, UI_SET_EVBIT, ev); err != nil {
			file.Close()
			return nil, err
		}
	}

	_ = setIoctl(fd, UI_SET_PROPBIT, INPUT_PROP_POINTER)
	_ = setIoctl(fd, UI_SET_PROPBIT, INPUT_PROP_BUTTONPAD)

	keys := []uintptr{
		BTN_LEFT, BTN_TOUCH,
		BTN_TOOL_FINGER, BTN_TOOL_DOUBLETAP, BTN_TOOL_TRIPLETAP, BTN_TOOL_QUADTAP, BTN_TOOL_QUINTTAP,
	}
	for _, key := range keys {
		_ = setIoctl(fd, UI_SET_KEYBIT, key)
	}

	abss := []uintptr{
		ABS_X, ABS_Y,
		ABS_MT_SLOT, ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_TRACKING_ID,
	}
	for _, abs := range abss {
		_ = setIoctl(fd, UI_SET_ABSBIT, abs)
	}

	_ = setupAbsAxis(fd, ABS_X, 0, 3000, 30)
	_ = setupAbsAxis(fd, ABS_Y, 0, 1800, 30)
	_ = setupAbsAxis(fd, ABS_MT_SLOT, 0, 9, 0)
	_ = setupAbsAxis(fd, ABS_MT_POSITION_X, 0, 3000, 30)
	_ = setupAbsAxis(fd, ABS_MT_POSITION_Y, 0, 1800, 30)
	_ = setupAbsAxis(fd, ABS_MT_TRACKING_ID, 0, 65535, 0)

	if err := createDevSetup(fd, "Phemic Virtual Touchpad", 0x03, 0x0001, 0x0002); err != nil {
		file.Close()
		return nil, err
	}

	if err := setIoctl(fd, UI_DEV_CREATE, 0); err != nil {
		file.Close()
		return nil, fmt.Errorf("UI_DEV_CREATE touchpad failed: %v", err)
	}
	return file, nil
}

func NewInputSystem(debug bool) (*InputSystem, error) {
	penFile, err := createPenDevice()
	if err != nil {
		return nil, fmt.Errorf("pen device: %v (run with sudo or add user to 'input' group)", err)
	}

	touchpadFile, err := createTouchpadDevice()
	if err != nil {
		penFile.Close()
		return nil, fmt.Errorf("touchpad device: %v", err)
	}

	fmt.Println("Virtual input devices initialized: Phemic Virtual Pen + Phemic Real Multi-Touch Touchpad")
	return &InputSystem{penFile: penFile, touchpadFile: touchpadFile, debug: debug, nextTrackingID: 1}, nil
}

func (in *InputSystem) sendEventTo(file *os.File, eventType, code uint16, value int32) error {
	if in.debug {
		typeStr := fmt.Sprintf("TYPE_%d", eventType)
		codeStr := fmt.Sprintf("CODE_%d", code)
		switch eventType {
		case EV_SYN:
			typeStr = "EV_SYN"
			if code == SYN_REPORT {
				codeStr = "SYN_REPORT"
			}
		case EV_KEY:
			typeStr = "EV_KEY"
			switch code {
			case BTN_LEFT:
				codeStr = "BTN_LEFT"
			case BTN_RIGHT:
				codeStr = "BTN_RIGHT"
			case BTN_MIDDLE:
				codeStr = "BTN_MIDDLE"
			case BTN_TOOL_PEN:
				codeStr = "BTN_TOOL_PEN"
			case BTN_TOUCH:
				codeStr = "BTN_TOUCH"
			case BTN_STYLUS:
				codeStr = "BTN_STYLUS"
			case BTN_STYLUS2:
				codeStr = "BTN_STYLUS2"
			case BTN_TOOL_FINGER:
				codeStr = "BTN_TOOL_FINGER"
			case BTN_TOOL_DOUBLETAP:
				codeStr = "BTN_TOOL_DOUBLETAP"
			case BTN_TOOL_TRIPLETAP:
				codeStr = "BTN_TOOL_TRIPLETAP"
			}
		case EV_ABS:
			typeStr = "EV_ABS"
			switch code {
			case ABS_X:
				codeStr = "ABS_X"
			case ABS_Y:
				codeStr = "ABS_Y"
			case ABS_PRESSURE:
				codeStr = "ABS_PRESSURE"
			case ABS_MT_SLOT:
				codeStr = "ABS_MT_SLOT"
			case ABS_MT_POSITION_X:
				codeStr = "ABS_MT_POSITION_X"
			case ABS_MT_POSITION_Y:
				codeStr = "ABS_MT_POSITION_Y"
			case ABS_MT_TRACKING_ID:
				codeStr = "ABS_MT_TRACKING_ID"
			}
		}
		fmt.Printf("  %-10s %-20s value=%+d\n", typeStr, codeStr, value)
	}

	now := time.Now()
	ev := inputEvent{
		Time: syscall.Timeval{
			Sec:  now.Unix(),
			Usec: int64(now.Nanosecond() / 1000),
		},
		Type:  eventType,
		Code:  code,
		Value: value,
	}

	buf := new(bytes.Buffer)
	if err := binary.Write(buf, binary.LittleEndian, ev); err != nil {
		return err
	}
	_, err := file.Write(buf.Bytes())
	return err
}

type TouchPointerData struct {
	ID       byte
	X        float32
	Y        float32
	Pressure float32
}

type TouchpadEvent struct {
	Mode       byte
	Action     byte
	X          float32
	Y          float32
	Pressure   float32
	Buttons    byte
	TouchCount byte
	ScrollDx   float32
	ScrollDy   float32
	Pointers   []TouchPointerData
}

func (in *InputSystem) HandleTouchpadEvent(evt TouchpadEvent) {
	in.mu.Lock()
	defer in.mu.Unlock()

	if in.penFile == nil || in.touchpadFile == nil {
		return
	}

	stylus1Down := (evt.Buttons & 0x01) != 0
	stylus2Down := (evt.Buttons & 0x02) != 0

	if in.debug {
		actionStr := "MOVE"
		switch evt.Action {
		case 0:
			actionStr = "DOWN"
		case 1:
			actionStr = "MOTION"
		case 2:
			actionStr = "UP"
		case 4:
			actionStr = "BUTTON"
		case 5:
			actionStr = "TAP_CLICK"
		}
		devStr := "PEN"
		if evt.Mode == 0 {
			devStr = "TOUCHPAD"
		}
		fmt.Printf("[INPUT] %-8s %-10s mode=%d x=%+7.2f y=%+7.2f pressure=%.2f touches=%d ptrs=%d buttons=0x%02x\n",
			devStr, actionStr, evt.Mode, evt.X, evt.Y, evt.Pressure, evt.TouchCount, len(evt.Pointers), evt.Buttons)
	}

	// Button-only events (stylus buttons)
	if evt.Action == 4 {
		if stylus1Down {
			_ = in.sendEventTo(in.penFile, EV_KEY, BTN_STYLUS, 1)
			_ = in.sendEventTo(in.penFile, EV_SYN, SYN_REPORT, 0)
			time.Sleep(50 * time.Millisecond)
			_ = in.sendEventTo(in.penFile, EV_KEY, BTN_STYLUS, 0)
			_ = in.sendEventTo(in.penFile, EV_SYN, SYN_REPORT, 0)
		}
		if stylus2Down {
			_ = in.sendEventTo(in.penFile, EV_KEY, BTN_STYLUS2, 1)
			_ = in.sendEventTo(in.penFile, EV_SYN, SYN_REPORT, 0)
			time.Sleep(50 * time.Millisecond)
			_ = in.sendEventTo(in.penFile, EV_KEY, BTN_STYLUS2, 0)
			_ = in.sendEventTo(in.penFile, EV_SYN, SYN_REPORT, 0)
		}
		return
	}

	// Action 5 = Tap Click
	if evt.Action == 5 {
		if (evt.Buttons & 0x02) != 0 {
			// Two-finger right click tap
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOOL_DOUBLETAP, 1)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOUCH, 1)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_LEFT, 1)
			_ = in.sendEventTo(in.touchpadFile, EV_SYN, SYN_REPORT, 0)
			time.Sleep(20 * time.Millisecond)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_LEFT, 0)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOUCH, 0)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOOL_DOUBLETAP, 0)
			_ = in.sendEventTo(in.touchpadFile, EV_SYN, SYN_REPORT, 0)
		} else {
			// Single-finger left click tap
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOOL_FINGER, 1)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOUCH, 1)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_LEFT, 1)
			_ = in.sendEventTo(in.touchpadFile, EV_SYN, SYN_REPORT, 0)
			time.Sleep(20 * time.Millisecond)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_LEFT, 0)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOUCH, 0)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOOL_FINGER, 0)
			_ = in.sendEventTo(in.touchpadFile, EV_SYN, SYN_REPORT, 0)
		}
		return
	}

	// Mode 1 = Pen tablet (absolute)
	if evt.Mode == 1 {
		absX := clamp32(int32(evt.X*65535), 0, 65535)
		absY := clamp32(int32(evt.Y*65535), 0, 65535)
		pressVal := clamp32(int32(evt.Pressure*4095), 0, 4095)

		switch evt.Action {
		case 0, 1: // DOWN or MOTION
			_ = in.sendEventTo(in.penFile, EV_KEY, BTN_TOOL_PEN, 1)
			_ = in.sendEventTo(in.penFile, EV_ABS, ABS_X, absX)
			_ = in.sendEventTo(in.penFile, EV_ABS, ABS_Y, absY)
			_ = in.sendEventTo(in.penFile, EV_ABS, ABS_PRESSURE, pressVal)
			_ = in.sendEventTo(in.penFile, EV_KEY, BTN_TOUCH, 1)
		case 2: // UP
			_ = in.sendEventTo(in.penFile, EV_ABS, ABS_PRESSURE, 0)
			_ = in.sendEventTo(in.penFile, EV_KEY, BTN_TOUCH, 0)
			_ = in.sendEventTo(in.penFile, EV_KEY, BTN_TOOL_PEN, 0)
		}
		_ = in.sendEventTo(in.penFile, EV_KEY, BTN_STYLUS, boolToInt32(stylus1Down))
		_ = in.sendEventTo(in.penFile, EV_KEY, BTN_STYLUS2, boolToInt32(stylus2Down))
		_ = in.sendEventTo(in.penFile, EV_SYN, SYN_REPORT, 0)
		return
	}

	// Mode 0 = Real Linux Multi-Touch Touchpad (Protocol B)
	if evt.Mode == 0 {
		activeCount := len(evt.Pointers)

		if evt.Action == 2 || activeCount == 0 { // ALL FINGERS LIFTED
			for slot := 0; slot < 10; slot++ {
				if in.slotTracking[slot] != 0 {
					_ = in.sendEventTo(in.touchpadFile, EV_ABS, ABS_MT_SLOT, int32(slot))
					_ = in.sendEventTo(in.touchpadFile, EV_ABS, ABS_MT_TRACKING_ID, -1)
					in.slotTracking[slot] = 0
				}
			}
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOUCH, 0)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOOL_FINGER, 0)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOOL_DOUBLETAP, 0)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOOL_TRIPLETAP, 0)
			_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOOL_QUADTAP, 0)
			_ = in.sendEventTo(in.touchpadFile, EV_SYN, SYN_REPORT, 0)
			return
		}

		currentActive := make(map[int32]bool)

		for i, ptr := range evt.Pointers {
			slot := int32(ptr.ID) % 10
			currentActive[slot] = true
			absX := clamp32(int32(ptr.X*3000), 0, 3000)
			absY := clamp32(int32(ptr.Y*1800), 0, 1800)

			if in.slotTracking[slot] == 0 {
				in.nextTrackingID++
				if in.nextTrackingID > 65000 {
					in.nextTrackingID = 1
				}
				in.slotTracking[slot] = in.nextTrackingID
			}
			trackingID := in.slotTracking[slot]

			_ = in.sendEventTo(in.touchpadFile, EV_ABS, ABS_MT_SLOT, slot)
			_ = in.sendEventTo(in.touchpadFile, EV_ABS, ABS_MT_TRACKING_ID, trackingID)
			_ = in.sendEventTo(in.touchpadFile, EV_ABS, ABS_MT_POSITION_X, absX)
			_ = in.sendEventTo(in.touchpadFile, EV_ABS, ABS_MT_POSITION_Y, absY)

			if i == 0 {
				_ = in.sendEventTo(in.touchpadFile, EV_ABS, ABS_X, absX)
				_ = in.sendEventTo(in.touchpadFile, EV_ABS, ABS_Y, absY)
			}
		}

		// Release any slot no longer present in active pointers
		for slot := int32(0); slot < 10; slot++ {
			if in.slotTracking[slot] != 0 && !currentActive[slot] {
				_ = in.sendEventTo(in.touchpadFile, EV_ABS, ABS_MT_SLOT, slot)
				_ = in.sendEventTo(in.touchpadFile, EV_ABS, ABS_MT_TRACKING_ID, -1)
				in.slotTracking[slot] = 0
			}
		}

		_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOUCH, 1)
		_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOOL_FINGER, boolToInt32(activeCount == 1))
		_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOOL_DOUBLETAP, boolToInt32(activeCount == 2))
		_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOOL_TRIPLETAP, boolToInt32(activeCount == 3))
		_ = in.sendEventTo(in.touchpadFile, EV_KEY, BTN_TOOL_QUADTAP, boolToInt32(activeCount >= 4))
		_ = in.sendEventTo(in.touchpadFile, EV_SYN, SYN_REPORT, 0)
	}
}

func clamp32(v, lo, hi int32) int32 {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func boolToInt32(b bool) int32 {
	if b {
		return 1
	}
	return 0
}

func (in *InputSystem) Close() {
	in.mu.Lock()
	defer in.mu.Unlock()
	if in.penFile != nil {
		_, _, _ = syscall.Syscall(syscall.SYS_IOCTL, in.penFile.Fd(), UI_DEV_DESTROY, 0)
		_ = in.penFile.Close()
		in.penFile = nil
	}
	if in.touchpadFile != nil {
		_, _, _ = syscall.Syscall(syscall.SYS_IOCTL, in.touchpadFile.Fd(), UI_DEV_DESTROY, 0)
		_ = in.touchpadFile.Close()
		in.touchpadFile = nil
	}
}

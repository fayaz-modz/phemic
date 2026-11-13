package main

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
)

type AudioSystem struct {
	micModuleID     string
	speakerModuleID string
	parecCmd        *exec.Cmd
	FifoFile        *os.File
	fifoPath        string
}

func NewAudioSystem() *AudioSystem {
	return &AudioSystem{}
}

func unloadExistingPhemicModules() {
	out, err := exec.Command("pactl", "list", "short", "modules").Output()
	if err != nil {
		return
	}
	lines := strings.Split(string(out), "\n")
	for _, line := range lines {
		if strings.Contains(line, "phemic_mic") || strings.Contains(line, "phemic_speaker") {
			fields := strings.Fields(line)
			if len(fields) > 0 {
				_ = exec.Command("pactl", "unload-module", fields[0]).Run()
			}
		}
	}
}

func (a *AudioSystem) Setup() error {
	unloadExistingPhemicModules()

	fifoPath := filepath.Join(os.TempDir(), fmt.Sprintf("phemic_mic_%d.fifo", os.Getuid()))
	_ = os.Remove(fifoPath)

	err := syscall.Mkfifo(fifoPath, 0666)
	if err != nil && !os.IsExist(err) && err != syscall.EEXIST {
		return fmt.Errorf("failed to create fifo: %v", err)
	}
	_ = os.Chmod(fifoPath, 0666)

	micCmd := exec.Command("pactl", "load-module", "module-pipe-source",
		"source_name=phemic_mic",
		"file="+fifoPath,
		"format=s16le",
		"rate=48000",
		"channels=1",
		"source_properties=device.description=\"Phemic_Virtual_Mic\"",
	)
	var micOut, micErr bytes.Buffer
	micCmd.Stdout = &micOut
	micCmd.Stderr = &micErr
	if err := micCmd.Run(); err != nil {
		return fmt.Errorf("failed to load module-pipe-source: %v (%s)", err, strings.TrimSpace(micErr.String()))
	}
	a.micModuleID = strings.TrimSpace(micOut.String())

	speakerCmd := exec.Command("pactl", "load-module", "module-null-sink",
		"sink_name=phemic_speaker",
		"sink_properties=device.description=\"Phemic_Virtual_Speaker\"",
		"format=s16le",
		"rate=48000",
		"channels=2",
	)
	var speakerOut, speakerErr bytes.Buffer
	speakerCmd.Stdout = &speakerOut
	speakerCmd.Stderr = &speakerErr
	if err := speakerCmd.Run(); err != nil {
		a.Cleanup()
		return fmt.Errorf("failed to load module-null-sink: %v (%s)", err, strings.TrimSpace(speakerErr.String()))
	}
	a.speakerModuleID = strings.TrimSpace(speakerOut.String())

	a.FifoFile, err = os.OpenFile(fifoPath, os.O_RDWR, 0666)
	if err != nil {
		a.Cleanup()
		return fmt.Errorf("failed to open fifo: %v", err)
	}

	a.fifoPath = fifoPath
	return nil
}

func (a *AudioSystem) StartRecordingSpeaker() (io.Reader, error) {
	a.parecCmd = exec.Command("parec",
		"-d", "phemic_speaker.monitor",
		"--format=s16le",
		"--rate=48000",
		"--channels=2",
		"--latency-msec=20",
	)
	stdout, err := a.parecCmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := a.parecCmd.Start(); err != nil {
		return nil, err
	}
	return stdout, nil
}

func (a *AudioSystem) Cleanup() {
	if a.parecCmd != nil && a.parecCmd.Process != nil {
		_ = a.parecCmd.Process.Kill()
	}
	if a.FifoFile != nil {
		_ = a.FifoFile.Close()
	}
	if a.micModuleID != "" {
		_ = exec.Command("pactl", "unload-module", a.micModuleID).Run()
	}
	if a.speakerModuleID != "" {
		_ = exec.Command("pactl", "unload-module", a.speakerModuleID).Run()
	}
	if a.fifoPath != "" {
		_ = os.Remove(a.fifoPath)
	}
}

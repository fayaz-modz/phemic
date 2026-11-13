package main

import (
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	debugFlag := flag.Bool("debug", false, "Enable verbose libinput-style event debug logging")
	flag.Parse()
	debugMode := *debugFlag

	fmt.Println("Starting Phemic Server...")
	if debugMode {
		fmt.Println("[DEBUG] Verbose libinput event debugging enabled.")
	}

	ips := getLocalIPs()
	if len(ips) > 0 {
		fmt.Println("Available Server IP Addresses:")
		for _, ip := range ips {
			fmt.Printf("  - %s\n", ip)
		}
	} else {
		fmt.Println("Warning: No local IP addresses found.")
	}
	fmt.Printf("Listening on UDP port %d...\n", Port)

	audio := NewAudioSystem()
	err := audio.Setup()
	if err != nil {
		fmt.Printf("Fatal error setting up virtual audio: %v\n", err)
		os.Exit(1)
	}

	input, err := NewInputSystem(debugMode)
	if err != nil {
		fmt.Printf("Warning: Failed to setup uinput touchpad device: %v\n", err)
	} else {
		fmt.Println("Virtual Touchpad/Tablet input device initialized successfully.")
	}

	network := NewNetworkSystem(audio, input, debugMode)
	err = network.Start()
	if err != nil {
		fmt.Printf("Fatal error starting network: %v\n", err)
		if input != nil {
			input.Close()
		}
		audio.Cleanup()
		os.Exit(1)
	}

	fmt.Println("Phemic Server is running. Press Ctrl+C to stop.")

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	fmt.Println("\nShutting down Phemic Server...")
	network.Close()
	if input != nil {
		input.Close()
	}
	audio.Cleanup()
	fmt.Println("Phemic Server stopped cleanly.")
}

func getLocalIPs() []string {
	var ips []string
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return ips
	}
	for _, address := range addrs {
		if ipnet, ok := address.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
			if ipnet.IP.To4() != nil {
				ips = append(ips, ipnet.IP.String())
			}
		}
	}
	return ips
}

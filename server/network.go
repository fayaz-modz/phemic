package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"math"
	"net"
	"os"
	"sync"
	"time"
)

const (
	Port = 9898

	PktDiscover     = 0x01
	PktHeartbeat    = 0x02
	PktAudioMic     = 0x10
	PktAudioSpeaker = 0x20
	PktKeepAlive    = 0x30
	PktDisconnect   = 0x40
	PktTouchpad     = 0x50
	PktMicControl   = 0x60
)

type NetworkSystem struct {
	audio                *AudioSystem
	input                *InputSystem
	conn                 *net.UDPConn
	activeMu             sync.RWMutex
	activeAddr           *net.UDPAddr
	lastActive           time.Time
	shutdownChan         chan struct{}
	triggerBroadcastChan chan struct{}
	debug                bool
}

func NewNetworkSystem(audio *AudioSystem, input *InputSystem, debug bool) *NetworkSystem {
	return &NetworkSystem{
		audio:                audio,
		input:                input,
		shutdownChan:         make(chan struct{}),
		triggerBroadcastChan: make(chan struct{}, 5),
		debug:                debug,
	}
}

func (n *NetworkSystem) Start() error {
	addr, err := net.ResolveUDPAddr("udp", fmt.Sprintf(":%d", Port))
	if err != nil {
		return err
	}

	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return err
	}
	n.conn = conn

	go n.receiveLoop()
	go n.speakerStreamLoop()
	go n.timeoutMonitor()
	go n.broadcastPresenceLoop()
	n.startControlListener()

	return nil
}

func (n *NetworkSystem) updateActiveAddr(remoteAddr *net.UDPAddr) {
	n.activeMu.Lock()
	defer n.activeMu.Unlock()

	n.lastActive = time.Now()
	if n.activeAddr == nil || n.activeAddr.String() != remoteAddr.String() {
		n.activeAddr = remoteAddr
		fmt.Printf("[NET] Client %s CONNECTED\n", remoteAddr)
	}
}

func (n *NetworkSystem) disconnectActiveAddr(remoteAddr *net.UDPAddr, reason string) {
	n.activeMu.Lock()

	if n.activeAddr != nil {
		if remoteAddr == nil || n.activeAddr.String() == remoteAddr.String() {
			fmt.Printf("[NET] Client %s DISCONNECTED (%s)\n", n.activeAddr, reason)
			n.activeAddr = nil
			n.activeMu.Unlock()

			// Signal broadcast presence loop to immediately restart scanning broadcast
			select {
			case n.triggerBroadcastChan <- struct{}{}:
			default:
			}
			return
		}
	}
	n.activeMu.Unlock()
}

type interfaceAddr struct {
	localIP     string
	broadcastIP string
}

func getInterfaceAddresses() []interfaceAddr {
	var addrs []interfaceAddr
	ifaces, err := net.Interfaces()
	if err != nil {
		return addrs
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		ifAddrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range ifAddrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok || ipnet.IP.To4() == nil {
				continue
			}
			ip := ipnet.IP.To4()
			mask := ipnet.Mask
			broadcast := make(net.IP, 4)
			for i := range ip {
				broadcast[i] = ip[i] | ^mask[i]
			}
			addrs = append(addrs, interfaceAddr{
				localIP:     ip.String(),
				broadcastIP: broadcast.String(),
			})
		}
	}
	return addrs
}

func (n *NetworkSystem) sendBroadcastPresence() {
	hostname, _ := os.Hostname()
	if hostname == "" {
		hostname = "Phemic Server"
	}
	packet := append([]byte{'P', 'H', 'M', PktHeartbeat}, []byte(hostname)...)

	bindings := getInterfaceAddresses()
	for _, bind := range bindings {
		localAddr, err := net.ResolveUDPAddr("udp", bind.localIP+":0")
		if err != nil {
			continue
		}
		dests := []string{bind.broadcastIP + ":9897", "255.255.255.255:9897"}
		for _, destStr := range dests {
			dest, err := net.ResolveUDPAddr("udp", destStr)
			if err != nil {
				continue
			}
			sock, err := net.DialUDP("udp", localAddr, dest)
			if err != nil {
				continue
			}
			_, _ = sock.Write(packet)
			_ = sock.Close()
		}
	}
}

func (n *NetworkSystem) broadcastPresenceLoop() {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-n.shutdownChan:
			return
		case <-n.triggerBroadcastChan:
			n.activeMu.RLock()
			isConnected := n.activeAddr != nil && time.Since(n.lastActive) < 10*time.Second
			n.activeMu.RUnlock()
			if !isConnected {
				if n.debug {
					fmt.Println("[DEBUG-NET] Triggering immediate presence broadcast after disconnect")
				}
				n.sendBroadcastPresence()
			}
		case <-ticker.C:
			n.activeMu.RLock()
			isConnected := n.activeAddr != nil && time.Since(n.lastActive) < 10*time.Second
			n.activeMu.RUnlock()

			if isConnected {
				continue
			}
			n.sendBroadcastPresence()
		}
	}
}

func (n *NetworkSystem) receiveLoop() {
	buf := make([]byte, 8192)
	for {
		select {
		case <-n.shutdownChan:
			return
		default:
		}

		length, remoteAddr, err := n.conn.ReadFromUDP(buf)
		if err != nil {
			continue
		}

		if length < 4 || string(buf[0:3]) != "PHM" {
			continue
		}

		pktType := buf[3]
		payload := buf[4:length]

		switch pktType {
		case PktDiscover:
			if n.debug {
				fmt.Printf("[DEBUG-NET] DISCOVER request from %s\n", remoteAddr)
			}
			hostname, _ := os.Hostname()
			if hostname == "" {
				hostname = "Phemic Server"
			}
			reply := append([]byte{'P', 'H', 'M', PktHeartbeat}, []byte(hostname)...)
			_, _ = n.conn.WriteToUDP(reply, remoteAddr)

		case PktKeepAlive:
			if n.debug {
				fmt.Printf("[DEBUG-NET] KEEP_ALIVE from %s\n", remoteAddr)
			}
			n.updateActiveAddr(remoteAddr)

		case PktDisconnect:
			if n.debug {
				fmt.Printf("[DEBUG-NET] DISCONNECT packet from %s\n", remoteAddr)
			}
			n.disconnectActiveAddr(remoteAddr, "Client explicitly disconnected")

		case PktAudioMic:
			if n.debug {
				fmt.Printf("[DEBUG-NET] AUDIO_MIC (%d bytes) from %s\n", len(payload), remoteAddr)
			}
			n.updateActiveAddr(remoteAddr)

			if n.audio.FifoFile != nil {
				_, _ = n.audio.FifoFile.Write(payload)
			}

		case PktTouchpad:
			if n.debug {
				fmt.Printf("[DEBUG-NET] TOUCHPAD (%d bytes) from %s\n", len(payload), remoteAddr)
			}
			n.updateActiveAddr(remoteAddr)

			if n.input != nil && len(payload) >= 4 {
				var evt TouchpadEvent
				evt.Mode = payload[0]
				evt.Action = payload[1]
				evt.Buttons = payload[2]
				evt.TouchCount = payload[3]

				ptrBytes := payload[4:]
				numPtrs := len(ptrBytes) / 13
				for i := 0; i < numPtrs; i++ {
					offset := i * 13
					var ptr TouchPointerData
					ptr.ID = ptrBytes[offset]
					ptr.X = math.Float32frombits(binary.LittleEndian.Uint32(ptrBytes[offset+1 : offset+5]))
					ptr.Y = math.Float32frombits(binary.LittleEndian.Uint32(ptrBytes[offset+5 : offset+9]))
					ptr.Pressure = math.Float32frombits(binary.LittleEndian.Uint32(ptrBytes[offset+9 : offset+13]))
					evt.Pointers = append(evt.Pointers, ptr)
				}

				if len(evt.Pointers) > 0 {
					evt.X = evt.Pointers[0].X
					evt.Y = evt.Pointers[0].Y
					evt.Pressure = evt.Pointers[0].Pressure
				}

				n.input.HandleTouchpadEvent(evt)
			}
		}
	}
}

func (n *NetworkSystem) speakerStreamLoop() {
	reader, err := n.audio.StartRecordingSpeaker()
	if err != nil {
		fmt.Printf("Error starting parec: %v\n", err)
		return
	}

	const chunkSize = 1920
	rawBuf := make([]byte, chunkSize)
	sendBuf := make([]byte, chunkSize+4)
	copy(sendBuf[0:3], "PHM")
	sendBuf[3] = PktAudioSpeaker

	for {
		select {
		case <-n.shutdownChan:
			return
		default:
		}

		_, err := io.ReadFull(reader, rawBuf)
		if err != nil {
			time.Sleep(10 * time.Millisecond)
			continue
		}

		n.activeMu.RLock()
		addr := n.activeAddr
		isActive := addr != nil && time.Since(n.lastActive) < 10*time.Second
		n.activeMu.RUnlock()

		if isActive {
			copy(sendBuf[4:], rawBuf)
			_, _ = n.conn.WriteTo(sendBuf, addr)
		}
	}
}

func (n *NetworkSystem) timeoutMonitor() {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-n.shutdownChan:
			return
		case <-ticker.C:
			n.activeMu.Lock()
			if n.activeAddr != nil && time.Since(n.lastActive) > 10*time.Second {
				addr := n.activeAddr
				n.activeAddr = nil
				n.activeMu.Unlock()

				fmt.Printf("[NET] Client %s DISCONNECTED (timed out)\n", addr)
				select {
				case n.triggerBroadcastChan <- struct{}{}:
				default:
				}
				continue
			}
			n.activeMu.Unlock()
		}
	}
}

func (n *NetworkSystem) SendMicControl(mode byte) error {
	n.activeMu.RLock()
	addr := n.activeAddr
	isActive := addr != nil && time.Since(n.lastActive) < 10*time.Second
	n.activeMu.RUnlock()

	if !isActive {
		return fmt.Errorf("no active phone connected")
	}

	pkt := []byte{'P', 'H', 'M', PktMicControl, mode}
	_, err := n.conn.WriteToUDP(pkt, addr)
	return err
}

func (n *NetworkSystem) startControlListener() {
	sockPath := "/tmp/phemic_ctl.sock"
	_ = os.Remove(sockPath)
	l, err := net.Listen("unix", sockPath)
	if err != nil {
		fmt.Printf("Warning: could not start control socket: %v\n", err)
		return
	}
	_ = os.Chmod(sockPath, 0666)

	go func() {
		<-n.shutdownChan
		_ = l.Close()
		_ = os.Remove(sockPath)
	}()

	go func() {
		buf := make([]byte, 16)
		for {
			conn, err := l.Accept()
			if err != nil {
				return
			}
			num, _ := conn.Read(buf)
			_ = conn.Close()
			if num > 0 {
				switch buf[0] {
				case '1':
					_ = n.SendMicControl(0x01)
				case '0':
					_ = n.SendMicControl(0x00)
				case 't':
					_ = n.SendMicControl(0x02)
				}
			}
		}
	}()
}

func (n *NetworkSystem) Close() {
	close(n.shutdownChan)
	if n.conn != nil {
		_ = n.conn.Close()
	}
}

package mobile

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"sync"

	"github.com/kianmhz/GooseRelayVPN/internal/carrier"
	"github.com/kianmhz/GooseRelayVPN/internal/config"
	"github.com/kianmhz/GooseRelayVPN/internal/session"
	isocks "github.com/kianmhz/GooseRelayVPN/internal/socks"
	"github.com/things-go/go-socks5"
	"github.com/things-go/go-socks5/statute"
	"github.com/xjasonlyu/tun2socks/v2/engine"
)

// Bandwidth holds upload and download statistics
type Bandwidth struct {
	Up   int64
	Down int64
}

var (
	mu              sync.Mutex
	cancelFn        context.CancelFunc
	socksLn         net.Listener
	running         bool
	tunActive       bool
	tunBridgeMu     sync.Mutex
	activeTunBridge *tunBridge
)

func StartClient(configPath string, logPath string) error {
	mu.Lock()
	if running {
		mu.Unlock()
		return fmt.Errorf("client is already running")
	}
	mu.Unlock()

	if err := os.MkdirAll(filepath.Dir(logPath), 0o755); err != nil {
		return fmt.Errorf("create log directory: %w", err)
	}
	logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return fmt.Errorf("open log file: %w", err)
	}
	defer logFile.Close()
	log.SetFlags(0)
	log.SetOutput(logFile)

	cfg, err := config.LoadClient(configPath)
	if err != nil {
		return err
	}

	carr, err := carrier.New(carrier.Config{
		ScriptURLs:  cfg.ScriptURLs,
		AESKeyHex:   cfg.AESKeyHex,
		DebugTiming: cfg.DebugTiming,
		Fronting: carrier.FrontingConfig{
			GoogleIP: cfg.GoogleIP,
			SNIHosts: cfg.SNIHosts,
		},
	})
	if err != nil {
		return fmt.Errorf("carrier init failed: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	mu.Lock()
	cancelFn = cancel
	running = true
	mu.Unlock()

	go func() {
		if err := carr.Run(ctx); err != nil && ctx.Err() == nil {
			log.Printf("[client] carrier run error: %v", err)
		}
	}()

	factory := isocks.SessionFactory(func(target string) *session.Session {
		return carr.NewSession(target)
	})

	server := socks5.NewServer(
		socks5.WithDial(func(_ context.Context, _, addr string) (net.Conn, error) {
			s := factory(addr)
			log.Printf("[socks] new session %x for %s", s.ID[:4], addr)
			return isocks.NewVirtualConn(s), nil
		}),
		socks5.WithAssociateHandle(func(_ context.Context, w io.Writer, _ *socks5.Request) error {
			_ = socks5.SendReply(w, statute.RepCommandNotSupported, nil)
			return fmt.Errorf("UDP associate not supported")
		}),
		socks5.WithResolver(noopResolver{}),
	)

	ln, lerr := net.Listen("tcp", cfg.ListenAddr)
	if lerr != nil {
		mu.Lock()
		running = false
		cancelFn = nil
		mu.Unlock()
		return lerr
	}
	mu.Lock()
	socksLn = ln
	mu.Unlock()

	go func() {
		<-ctx.Done()
		mu.Lock()
		if socksLn != nil {
			_ = socksLn.Close()
		}
		mu.Unlock()
	}()

	err = server.Serve(ln)

	mu.Lock()
	running = false
	cancelFn = nil
	socksLn = nil
	mu.Unlock()
	return err
}

func StopClient() {
	StopTun()
	mu.Lock()
	defer mu.Unlock()
	if cancelFn != nil {
		cancelFn()
	}
	if socksLn != nil {
		_ = socksLn.Close()
		socksLn = nil
	}
}

func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

func StartTun(fd int64, proxyAddr string) {
	key := &engine.Key{
		Proxy:  "socks5://" + proxyAddr,
		Device: fmt.Sprintf("fd://%d", fd),
		MTU:    1500,
	}
	engine.Insert(key)
	engine.Start()

	mu.Lock()
	tunActive = true
	mu.Unlock()
}

func StopTun() {
	mu.Lock()
	defer mu.Unlock()
	if tunActive {
		engine.Stop()
		tunActive = false
	}
}

// TUN Bridge functions

// StartTunBridge starts the TUN bridge with DNS interception
func StartTunBridge(tunFd int64, mtu int64, socksAddr string) error {
	tunBridgeMu.Lock()
	defer tunBridgeMu.Unlock()

	if activeTunBridge != nil {
		return fmt.Errorf("TUN bridge already running")
	}

	if socksAddr == "" {
		return fmt.Errorf("socksAddr cannot be empty")
	}

	if tunFd < 0 {
		return fmt.Errorf("invalid tunFd: %d", tunFd)
	}

	log.Printf("[API] Starting TUN bridge: fd=%d mtu=%d socks=%s", tunFd, mtu, socksAddr)

	bridge := newTunBridge(int(tunFd), socksAddr, nil)
	if err := bridge.start(); err != nil {
		return fmt.Errorf("failed to start bridge: %v", err)
	}

	activeTunBridge = bridge
	log.Printf("[API] TUN bridge started successfully")
	return nil
}

// StopTunBridge stops the TUN bridge
func StopTunBridge() {
	tunBridgeMu.Lock()
	defer tunBridgeMu.Unlock()

	if activeTunBridge == nil {
		return
	}

	log.Printf("[API] Stopping TUN bridge")
	activeTunBridge.stop()
	activeTunBridge = nil
	log.Printf("[API] TUN bridge stopped")
}

// IsTunBridgeRunning returns true if bridge is active
func IsTunBridgeRunning() bool {
	tunBridgeMu.Lock()
	defer tunBridgeMu.Unlock()
	return activeTunBridge != nil
}

// GetTunBandwidth returns upload and download bandwidth statistics
func GetTunBandwidth() *Bandwidth {
	tunBridgeMu.Lock()
	defer tunBridgeMu.Unlock()

	if activeTunBridge == nil {
		return &Bandwidth{Up: 0, Down: 0}
	}

	up, down := activeTunBridge.getBandwidth()
	return &Bandwidth{Up: up, Down: down}
}

// GetDNSMapping returns the hostname for a fake IP
func GetDNSMapping(fakeIP string) string {
	tunBridgeMu.Lock()
	defer tunBridgeMu.Unlock()

	if activeTunBridge == nil {
		return ""
	}

	hostname, ok := activeTunBridge.dnsMap.GetHostname(fakeIP)
	if !ok {
		return ""
	}
	return hostname
}

// GetDNSMappingCount returns the number of DNS mappings
func GetDNSMappingCount() int {
	tunBridgeMu.Lock()
	defer tunBridgeMu.Unlock()

	if activeTunBridge == nil {
		return 0
	}

	activeTunBridge.dnsMap.mu.RLock()
	count := len(activeTunBridge.dnsMap.hostnameToIP)
	activeTunBridge.dnsMap.mu.RUnlock()
	return count
}

// GetTunVersion returns the TUN module version
func GetTunVersion() string {
	return "1.0.0"
}

type noopResolver struct{}

func (noopResolver) Resolve(ctx context.Context, _ string) (context.Context, net.IP, error) {
	return ctx, nil, nil
}

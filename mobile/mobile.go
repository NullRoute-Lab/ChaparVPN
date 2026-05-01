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

	"github.com/kianmhz/GooseRelayVPN/mobile/tun"
	"github.com/kianmhz/GooseRelayVPN/internal/carrier"
	"github.com/kianmhz/GooseRelayVPN/internal/config"
	"github.com/kianmhz/GooseRelayVPN/internal/session"
	isocks "github.com/kianmhz/GooseRelayVPN/internal/socks"
	"github.com/things-go/go-socks5"
	"github.com/things-go/go-socks5/statute"
	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	mu        sync.Mutex
	cancelFn  context.CancelFunc
	socksLn   net.Listener
	running   bool
	tunActive bool
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

// TUN Bridge functions (wrapper for mobile/tun package)

// StartTunBridge starts the TUN bridge with DNS interception
func StartTunBridge(tunFd int64, mtu int64, socksAddr string) error {
	return tun.StartTunBridge(int32(tunFd), int32(mtu), socksAddr)
}

// StopTunBridge stops the TUN bridge
func StopTunBridge() {
	tun.StopTunBridge()
}

// IsTunBridgeRunning returns true if bridge is active
func IsTunBridgeRunning() bool {
	return tun.IsTunBridgeRunning()
}

// GetTunBandwidth returns upload and download bytes
func GetTunBandwidth() (up int64, down int64) {
	return tun.GetTunBandwidth()
}

// GetDNSMapping returns the hostname for a fake IP
func GetDNSMapping(fakeIP string) string {
	return tun.GetDNSMapping(fakeIP)
}

// GetDNSMappingCount returns the number of DNS mappings
func GetDNSMappingCount() int {
	return tun.GetDNSMappingCount()
}

// GetTunVersion returns the TUN module version
func GetTunVersion() string {
	return tun.GetVersion()
}

type noopResolver struct{}

func (noopResolver) Resolve(ctx context.Context, _ string) (context.Context, net.IP, error) {
	return ctx, nil, nil
}

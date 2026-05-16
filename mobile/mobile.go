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
	"time"

	"github.com/nullroute-lab/gooserelayvpn-androidclient/mobile/tun"
	"github.com/nullroute-lab/gooserelayvpn-androidclient/mobile/core/carrier"
	"github.com/nullroute-lab/gooserelayvpn-androidclient/mobile/core/config"
	"github.com/nullroute-lab/gooserelayvpn-androidclient/mobile/core/session"
	isocks "github.com/nullroute-lab/gooserelayvpn-androidclient/mobile/core/socks"
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

// Bandwidth holds upload and download counters.
type Bandwidth struct {
	Up   int64
	Down int64
}

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
		ScriptURLs:       cfg.ScriptURLs,
		ScriptAccounts:   cfg.ScriptAccounts,
		AESKeyHex:        cfg.AESKeyHex,
		DebugTiming:      cfg.DebugTiming,
		CoalesceStep:     time.Duration(cfg.CoalesceStepMs) * time.Millisecond,
		CoalesceMax:      time.Duration(cfg.CoalesceMaxMs) * time.Millisecond,
		IdleSlotsPerBucket: cfg.IdleSlotsPerBucket,
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

	var serverOpts []socks5.Option
	serverOpts = append(serverOpts,
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

	if cfg.SocksUser != "" && cfg.SocksPass != "" {
		serverOpts = append(serverOpts, socks5.WithAuthMethods([]socks5.Authenticator{
			socks5.UserPassAuthenticator{
				Credentials: socks5.StaticCredentials{
					cfg.SocksUser: cfg.SocksPass,
				},
			},
		}))
		log.Printf("[socks] authentication enabled: user=%s", cfg.SocksUser)
	}

	server := socks5.NewServer(serverOpts...)

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

// TUN Bridge wrapper functions (calls mobile/tun subpackage)

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

// GetTunBandwidth returns upload and download bytes.
func GetTunBandwidth() *Bandwidth {
	up, down := tun.GetTunBandwidth()
	return &Bandwidth{Up: up, Down: down}
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

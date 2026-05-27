package socks

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"sync/atomic"

	"github.com/nullroute-lab/chaparvpn-androidclient/mobile/core/session"
	"github.com/things-go/go-socks5"
	"github.com/things-go/go-socks5/statute"
)

// SessionFactory creates a new tunneled session for the given "host:port"
// target. The returned session is owned by the carrier (which polls it for
// outgoing frames and routes incoming ones).
type SessionFactory func(target string) *session.Session

// Serve starts a SOCKS5 listener on listenAddr that wraps every connection in
// a VirtualConn over a fresh tunneled session. The DNS resolver is overridden
// with a no-op to prevent local DNS leaks (clients must use socks5h://).
//
// Wraps the listener with a TCP_NODELAY + TCP_QUICKACK applying acceptor so
// the kernel doesn't introduce 40 ms Nagle delays on small SOCKS payloads
// (HTTP request lines, TLS handshake records) and doesn't hold back ACKs for
// up to 40 ms on small request/reply pairs. The exit side already disables
// Nagle for upstream connections; mirroring on the local side closes the loop.
//
// When user and pass are both non-empty, RFC 1929 username/password
// authentication is required; unauthenticated clients are rejected.
//
// Blocks until ListenAndServe returns. Caller passes ctx for shutdown
// signaling (the underlying go-socks5 library doesn't take a ctx, so this
// just wires it through for parity with the rest of the codebase).
func Serve(_ context.Context, listenAddr, user, pass string, debugTiming bool, maxSessions int, factory SessionFactory) error {
	var activeSessions atomic.Int32

	if maxSessions == -1 {
		log.Printf("[socks] Warning: max_active_sessions is set to -1. Connection storm protection is DISABLED. Infinite sessions allowed.")
	}

	opts := []socks5.Option{
		socks5.WithDial(func(_ context.Context, _, addr string) (net.Conn, error) {
			current := activeSessions.Add(1)
			if maxSessions != -1 && current > int32(maxSessions) {
				activeSessions.Add(-1)
				return nil, fmt.Errorf("max active sessions reached (%d)", maxSessions)
			}
			s := factory(addr)
			if debugTiming {
				log.Printf("[socks] new session %x for %s", s.ID[:4], addr)
			}
			conn := NewVirtualConn(s)
			// Need to track when VirtualConn closes to decrement.
			// Wrap VirtualConn to hook Close().
			return &trackedConn{
				Conn: conn,
				onClose: func() {
					activeSessions.Add(-1)
				},
			}, nil
		}),
		socks5.WithAssociateHandle(func(ctx context.Context, w io.Writer, req *socks5.Request) error {
			current := activeSessions.Add(1)
			if maxSessions != -1 && current > int32(maxSessions) {
				activeSessions.Add(-1)
				_ = socks5.SendReply(w, statute.RepServerFailure, nil)
				return fmt.Errorf("max active sessions reached (%d)", maxSessions)
			}
			defer activeSessions.Add(-1)

			// SOCKS5 UDP ASSOCIATE
			// Bind to an ephemeral port on the same IP as the SOCKS listener (or 127.0.0.1 if unspecified)
			listenIP := net.ParseIP(listenAddr)
			if listenIP == nil {
				host, _, err := net.SplitHostPort(listenAddr)
				if err == nil {
					listenIP = net.ParseIP(host)
				}
			}
			if listenIP == nil || listenIP.IsUnspecified() || listenIP.To4() == nil {
				listenIP = net.ParseIP("127.0.0.1")
			}
			listenIP = listenIP.To4()
			udpAddr := &net.UDPAddr{IP: listenIP, Port: 0}
			udpConn, err := net.ListenUDP("udp4", udpAddr)
			if err != nil {
				_ = socks5.SendReply(w, statute.RepServerFailure, nil)
				return fmt.Errorf("failed to bind UDP listener: %w", err)
			}
			defer udpConn.Close()

			bndAddr := udpConn.LocalAddr().(*net.UDPAddr)

			// Tell the client where to send UDP datagrams
			var atyp byte
			if bndAddr.IP.To4() != nil {
				atyp = statute.ATYPIPv4
			} else {
				atyp = statute.ATYPIPv6
			}

			// Let's implement net.Addr to pass to SendReply
			var addr net.Addr = &udpAddrWrapper{
				ip:   bndAddr.IP,
				port: bndAddr.Port,
				atyp: atyp,
			}

			if err := socks5.SendReply(w, statute.RepSuccess, addr); err != nil {
				return fmt.Errorf("failed to send UDP associate reply: %w", err)
			}

			// We need a session to tunnel the UDP traffic. We use the req.DestAddr.String() as a placeholder target.
			// Actually, SOCKS5 UDP ASSOCIATE doesn't need a single target, packets dictate their own destination.
			// The original TCP request DestAddr is often all zeros.
			target := req.DestAddr.String()
			if target == "" || target == "0.0.0.0:0" || target == "[::]:0" {
				target = "udp_associate"
			}
			s := factory(target)
			if debugTiming {
				log.Printf("[socks] new UDP session %x", s.ID[:4])
			}
			defer func() {
				s.CloseRx()
				s.RequestClose()
			}()

			errCh := make(chan error, 3)

			var clientUDPAddr *net.UDPAddr
			var addrMu sync.Mutex

			// Client -> UDP listener -> Tunnel
			go func() {
				buf := make([]byte, 65535)
				for {
					n, clientAddr, err := udpConn.ReadFromUDP(buf)
					if err != nil {
						errCh <- err
						return
					}
					addrMu.Lock()
					if clientUDPAddr == nil || clientUDPAddr.String() != clientAddr.String() {
						clientUDPAddr = clientAddr
					}
					addrMu.Unlock()

					// Strip SOCKS5 UDP header (RSV(2), FRAG(1))
					if n < 3 {
						continue
					}
					frag := buf[2]
					if frag != 0 {
						// Fragmentation not supported
						continue
					}
					// Send everything from ATYP onwards.
					// We must copy the buffer because EnqueueUDP takes ownership or might delay sending.
					payload := make([]byte, n-3)
					copy(payload, buf[3:n])

					// Protocol-Aware Batching Bypass: check if destination port is 53 (DNS)
					urgent := false
					if len(payload) > 1 { // at least ATYP
						atyp := payload[0]
						var port int
						switch atyp {
						case statute.ATYPIPv4:
							if len(payload) >= 1+4+2 {
								port = int(payload[1+4])<<8 | int(payload[1+4+1])
							}
						case statute.ATYPIPv6:
							if len(payload) >= 1+16+2 {
								port = int(payload[1+16])<<8 | int(payload[1+16+1])
							}
						case statute.ATYPDomain:
							if len(payload) >= 2 {
								domainLen := int(payload[1])
								if len(payload) >= 2+domainLen+2 {
									port = int(payload[2+domainLen])<<8 | int(payload[2+domainLen+1])
								}
							}
						}
						if port == 53 {
							urgent = true
						}
					}

					s.EnqueueUDP(payload, urgent)
				}
			}()

			// Tunnel -> UDP listener -> Client
			go func() {
				for datagram := range s.RxUDPChan {
					addrMu.Lock()
					dstAddr := clientUDPAddr
					addrMu.Unlock()

					if dstAddr == nil {
						// Drop if we don't know where the client is yet
						continue
					}

					// Prepend SOCKS5 UDP header (RSV=0x00 0x00, FRAG=0x00)
					msg := make([]byte, 3+len(datagram))
					msg[0] = 0
					msg[1] = 0
					msg[2] = 0
					copy(msg[3:], datagram)

					_, err := udpConn.WriteToUDP(msg, dstAddr)
					if err != nil {
						errCh <- err
						return
					}
				}
				errCh <- nil
			}()

			// Keep the TCP control connection open until EOF or an error occurs on UDP
			// SOCKS5 client will keep this TCP connection open. We block here.
			// The original TCP request's stream needs to be read to block and detect disconnects.
			go func() {
				// Actually we can read from the context or the underlying conn if available.
				// io.Writer `w` in WithAssociateHandle is the same underlying conn which is io.ReadWriter.
				if rw, ok := w.(io.Reader); ok {
					io.Copy(io.Discard, rw)
				}
				errCh <- nil
			}()

			err = <-errCh
			return err
		}),
		socks5.WithResolver(noopResolver{}),
	}
	if user != "" {
		opts = append(opts, socks5.WithAuthMethods([]socks5.Authenticator{
			socks5.UserPassAuthenticator{
				Credentials: socks5.StaticCredentials{user: pass},
			},
		}))
	}

	ln, err := net.Listen(listenNetwork(listenAddr), listenAddr)
	if err != nil {
		return err
	}
	server := socks5.NewServer(opts...)
	return server.Serve(&noDelayListener{Listener: ln})
}

// listenNetwork picks the right network family for net.Listen based on the
// literal address. Defaulting to "tcp" causes Go to bind an AF_INET6 socket
// with V4MAPPED even for explicit IPv4 addresses like "0.0.0.0"; on Linux
// hosts where net.ipv6.bindv6only=1, that socket then refuses IPv4
// connections (issues #94 and #111). Forcing "tcp4" / "tcp6" when the host
// is an IP literal sidesteps that, while leaving hostnames on "tcp" so
// resolver-driven setups (e.g. "localhost") still work.
func listenNetwork(addr string) string {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return "tcp"
	}
	if host == "" {
		return "tcp" // bare ":1080" — let Go pick
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return "tcp"
	}
	if ip.To4() != nil {
		return "tcp4"
	}
	return "tcp6"
}

// noDelayListener wraps net.Listener so each accepted *net.TCPConn has both
// SetNoDelay(true) and (on Linux) TCP_QUICKACK applied. This eliminates the
// kernel's 40 ms Nagle delay on small SOCKS write payloads and the 40 ms
// delayed-ACK on small read replies — together they cover both directions
// of every interactive request/reply pair (DNS-over-HTTPS, REST GETs, TLS
// handshake records).
type noDelayListener struct {
	net.Listener
}

func (l *noDelayListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	if tcp, ok := c.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}
	setQuickAck(c)
	return c, nil
}

type udpAddrWrapper struct {
	ip   net.IP
	port int
	atyp byte
}

func (a *udpAddrWrapper) Network() string { return "udp" }
func (a *udpAddrWrapper) String() string  { return fmt.Sprintf("%s:%d", a.ip.String(), a.port) }

// noopResolver is a SOCKS5 name resolver that returns the host string verbatim
// (no DNS lookup). Combined with socks5h:// clients, this keeps DNS off the
// local machine entirely — it's resolved on the VPS exit instead.
type noopResolver struct{}

func (noopResolver) Resolve(ctx context.Context, _ string) (context.Context, net.IP, error) {
	return ctx, nil, nil
}

type trackedConn struct {
	net.Conn
	onClose func()
	once    sync.Once
}

func (c *trackedConn) Close() error {
	c.once.Do(c.onClose)
	return c.Conn.Close()
}

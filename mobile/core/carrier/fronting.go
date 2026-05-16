package carrier

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"sort"
	"strings"
	"time"

	utls "github.com/refraction-networking/utls"
	"golang.org/x/net/http2"
)

const frontedProbeOKBody = "GooseRelay forwarder OK"

type FrontingConfig struct {
	GoogleIP string
	SNIHosts []string
}

func NewFrontedClients(cfg FrontingConfig, pollTimeout time.Duration, probeURL string) []*http.Client {
	hosts := cfg.SNIHosts
	if len(hosts) == 0 {
		hosts = []string{"www.google.com"}
	}
	caches := make(map[string]utls.ClientSessionCache, len(hosts))
	for _, sni := range hosts {
		if _, ok := caches[sni]; !ok {
			caches[sni] = utls.NewLRUClientSessionCache(8)
		}
	}
	clients := make([]*http.Client, len(hosts))
	for i, sni := range hosts {
		clients[i] = newFrontedClient(cfg.GoogleIP, sni, pollTimeout, caches[sni])
	}
	hosts, clients = filterFrontedClientsByProbe(hosts, clients, probeURL)

	prewarmFrontedClients(cfg.GoogleIP, hosts, caches)
	return clients
}

type frontedProbeResult struct {
	index   int
	host    string
	client  *http.Client
	samples []time.Duration
	err     error
}

func (r frontedProbeResult) ok() bool {
	return len(r.samples) > 0
}

func (r frontedProbeResult) latency() time.Duration {
	if len(r.samples) == 0 {
		return 0
	}
	sorted := append([]time.Duration(nil), r.samples...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
	return sorted[len(sorted)/2]
}

func filterFrontedClientsByProbe(hosts []string, clients []*http.Client, probeURL string) ([]string, []*http.Client) {
	if len(hosts) <= 1 || probeURL == "" {
		return hosts, clients
	}

	results := probeFrontedClients(hosts, clients, probeURL)
	keep := selectFrontedClientIndexes(results)
	if len(keep) == 0 {
		return hosts, clients
	}

	keptHosts := make([]string, 0, len(keep))
	keptClients := make([]*http.Client, 0, len(keep))
	kept := make(map[int]struct{}, len(keep))
	for _, idx := range keep {
		kept[idx] = struct{}{}
		keptHosts = append(keptHosts, hosts[idx])
		keptClients = append(keptClients, clients[idx])
	}

	logFrontedProbeDecision(results, keep)
	return keptHosts, keptClients
}

func probeFrontedClients(hosts []string, clients []*http.Client, probeURL string) []frontedProbeResult {
	const (
		probeSamples = 2
		probeTimeout = 8 * time.Second
	)
	results := make([]frontedProbeResult, len(hosts))
	resultCh := make(chan frontedProbeResult, len(hosts))
	for i, host := range hosts {
		client := clients[i]
		go func(index int, sniHost string, httpClient *http.Client) {
			res := frontedProbeResult{index: index, host: sniHost, client: httpClient}
			for sample := 0; sample < probeSamples; sample++ {
				ctx, cancel := context.WithTimeout(context.Background(), probeTimeout)
				req, err := http.NewRequestWithContext(ctx, http.MethodGet, probeURL, nil)
				if err != nil {
					cancel()
					res.err = err
					break
				}
				start := time.Now()
				resp, err := httpClient.Do(req)
				if err != nil {
					cancel()
					res.err = err
					continue
				}
				body, readErr := io.ReadAll(resp.Body)
				_ = resp.Body.Close()
				if readErr != nil {
					cancel()
					res.err = readErr
					continue
				}
				if err := validateFrontedProbeResponse(resp.StatusCode, body); err != nil {
					cancel()
					res.err = err
					continue
				}
				res.samples = append(res.samples, time.Since(start))
				cancel()
			}
			resultCh <- res
		}(i, host, client)
	}

	for range hosts {
		res := <-resultCh
		results[res.index] = res
	}
	return results
}

func validateFrontedProbeResponse(statusCode int, body []byte) error {
	if statusCode != http.StatusOK {
		return fmt.Errorf("unexpected probe status %d", statusCode)
	}
	if strings.TrimSpace(string(body)) != frontedProbeOKBody {
		return fmt.Errorf("unexpected probe body %q", strings.TrimSpace(string(body)))
	}
	return nil
}

func selectFrontedClientIndexes(results []frontedProbeResult) []int {
	if len(results) <= 1 {
		return allFrontedClientIndexes(len(results))
	}

	successes := make([]frontedProbeResult, 0, len(results))
	for _, res := range results {
		if res.ok() {
			successes = append(successes, res)
		}
	}
	if len(successes) == 0 {
		return allFrontedClientIndexes(len(results))
	}
	if len(successes) == 1 {
		return []int{successes[0].index}
	}

	latencies := make([]time.Duration, 0, len(successes))
	for _, res := range successes {
		latencies = append(latencies, res.latency())
	}
	sort.Slice(latencies, func(i, j int) bool { return latencies[i] < latencies[j] })
	median := latencies[len(latencies)/2]
	if len(successes) <= 2 || median <= 0 {
		return indexesForFrontedResults(successes)
	}

	threshold := 3 * median
	kept := make([]int, 0, len(successes))
	for _, res := range successes {
		if res.latency() <= threshold {
			kept = append(kept, res.index)
		}
	}
	if len(kept) < 2 {
		return indexesForFrontedResults(successes)
	}
	return kept
}

func indexesForFrontedResults(results []frontedProbeResult) []int {
	indexes := make([]int, 0, len(results))
	for _, res := range results {
		indexes = append(indexes, res.index)
	}
	return indexes
}

func allFrontedClientIndexes(n int) []int {
	indexes := make([]int, n)
	for i := range indexes {
		indexes[i] = i
	}
	return indexes
}

func logFrontedProbeDecision(results []frontedProbeResult, keep []int) {
	kept := make(map[int]struct{}, len(keep))
	for _, idx := range keep {
		kept[idx] = struct{}{}
	}
	latencies := make([]time.Duration, 0, len(results))
	for _, res := range results {
		if res.ok() {
			latencies = append(latencies, res.latency())
		}
	}
	if len(latencies) > 0 {
		sort.Slice(latencies, func(i, j int) bool { return latencies[i] < latencies[j] })
	}
	median := time.Duration(0)
	if len(latencies) > 0 {
		median = latencies[len(latencies)/2]
	}
	for _, res := range results {
		action := "drop"
		if _, ok := kept[res.index]; ok {
			action = "keep"
		}
		if res.ok() {
			log.Printf("[fronting] startup probe %s sni=%s ttfb=%s samples=%d", action, res.host, res.latency().Round(time.Millisecond), len(res.samples))
			continue
		}
		if res.err != nil {
			log.Printf("[fronting] startup probe %s sni=%s err=%v", action, res.host, res.err)
			continue
		}
		log.Printf("[fronting] startup probe %s sni=%s no-successful-samples", action, res.host)
	}
	log.Printf("[fronting] startup probe kept %d/%d sni hosts median_ttfb=%s", len(keep), len(results), median.Round(time.Millisecond))
}

func prewarmFrontedClients(googleIP string, sniHosts []string, caches map[string]utls.ClientSessionCache) {
	const (
		dialTimeout   = 3 * time.Second
		ticketWindow  = 500 * time.Millisecond
		overallBudget = 5 * time.Second
	)
	dialer := &net.Dialer{Timeout: dialTimeout}
	for _, sni := range sniHosts {
		go func(sniHost string, cache utls.ClientSessionCache) {
			ctx, cancel := context.WithTimeout(context.Background(), overallBudget)
			defer cancel()
			addr := googleIP
			if addr == "" {
				addr = net.JoinHostPort(sniHost, "443")
			}
			rawConn, err := dialer.DialContext(ctx, "tcp", addr)
			if err != nil {
				return
			}
			defer rawConn.Close()

			uconn := utls.UClient(rawConn, &utls.Config{
				ServerName:         sniHost,
				ClientSessionCache: cache,
			}, utls.HelloChrome_Auto)

			if err := uconn.HandshakeContext(ctx); err != nil {
				return
			}

			_ = uconn.SetReadDeadline(time.Now().Add(ticketWindow))
			var buf [1]byte
			_, _ = uconn.Read(buf[:])
		}(sni, caches[sni])
	}
}

// customTestRoundTripper wraps the h2 transport but falls back to h1 for local test servers.
type customTestRoundTripper struct {
	h2 *http2.Transport
	h1 *http.Transport
}

func (c *customTestRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	host := req.URL.Hostname()
	if host == "127.0.0.1" || host == "localhost" {
		port := req.URL.Port()
		if port != "443" && port != "" {
			return c.h1.RoundTrip(req)
		}
	}
	return c.h2.RoundTrip(req)
}

func newFrontedClient(googleIP, sniHost string, pollTimeout time.Duration, sessionCache utls.ClientSessionCache) *http.Client {
	dialer := &net.Dialer{Timeout: 30 * time.Second, KeepAlive: 30 * time.Second}

	t2 := &http2.Transport{
		AllowHTTP: true,
		DialTLSContext: func(ctx context.Context, network, addr string, cfg *tls.Config) (net.Conn, error) {
			dialTarget := googleIP
			if dialTarget == "" {
				dialTarget = addr
			}
			rawConn, err := dialer.DialContext(ctx, "tcp", dialTarget)
			if err != nil {
				return nil, fmt.Errorf("fronted dial %s: %w", dialTarget, err)
			}

			uconn := utls.UClient(rawConn, &utls.Config{
				ServerName:         sniHost,
				ClientSessionCache: sessionCache,
			}, utls.HelloChrome_Auto)

			if err := uconn.HandshakeContext(ctx); err != nil {
				rawConn.Close()
				return nil, fmt.Errorf("utls handshake SNI=%s: %w", sniHost, err)
			}

			cs := uconn.ConnectionState()
			if cs.NegotiatedProtocol != "h2" {
				rawConn.Close()
				return nil, fmt.Errorf(
					"utls ALPN mismatch: got %q, require h2 (SNI=%s, target=%s)",
					cs.NegotiatedProtocol, sniHost, dialTarget,
				)
			}

			return uconn, nil
		},
		ReadIdleTimeout: 30 * time.Second,
		PingTimeout:     15 * time.Second,
	}

	t1 := &http.Transport{
		DialContext: dialer.DialContext,
		ForceAttemptHTTP2: false,
	}

	rt := &customTestRoundTripper{
		h2: t2,
		h1: t1,
	}

	return &http.Client{Transport: rt, Timeout: pollTimeout}
}

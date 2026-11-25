package main

import (
    "context"
    "crypto/tls"
    "crypto/x509"
    "net"
    "net/http"
    "net/http/httptest"
    "testing"
    "time"
)

func TestVerifiedWebSocketTLS(t *testing.T) {
    server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
    defer server.Close()
    roots := x509.NewCertPool()
    roots.AddCert(server.Certificate())
    for _, scenario := range []struct { name, host string; trust, accept bool }{
        {"trusted", "127.0.0.1", true, true},
        {"wrong-host", "wrong.example", true, false},
        {"untrusted", "127.0.0.1", false, false},
    } {
        t.Run(scenario.name, func(t *testing.T) {
            config := verifiedWsTLSConfig(scenario.host)
            if config.InsecureSkipVerify { t.Fatal("TLS verification disabled") }
            if scenario.trust { config.RootCAs = roots } else { config.RootCAs = x509.NewCertPool() }
            conn, err := tls.Dial("tcp", server.Listener.Addr().String(), config)
            if conn != nil { conn.Close() }
            if (err == nil) != scenario.accept { t.Fatalf("TLS outcome: %v", err) }
        })
    }
}

func TestCancelClosesHalfOpenClientAndListener(t *testing.T) {
    initLogging(false)
    cfproxyEnabled = false // Host test must never contact external servers.
    listener, err := net.Listen("tcp", "127.0.0.1:0")
    if err != nil { t.Fatal(err) }
    address := listener.Addr().String()
    port := listener.Addr().(*net.TCPAddr).Port
    listener.Close()
    ctx, cancel := context.WithCancel(context.Background())
    defer cancel()
    started := make(chan error, 1)
    done := make(chan error, 1)
    go func() { done <- runProxy(ctx, "127.0.0.1", port, map[int]string{}, started) }()
    if err := <-started; err != nil { t.Fatal(err) }
    client, err := net.Dial("tcp", address)
    if err != nil { t.Fatal(err) }
    defer client.Close()
    // Let Accept register a client blocked before its first protocol byte.
    time.Sleep(50 * time.Millisecond)
    cancel()
    select {
    case <-done:
    case <-time.After(2 * time.Second): t.Fatal("proxy did not stop promptly")
    }
    client.SetReadDeadline(time.Now().Add(time.Second))
    if _, err := client.Read(make([]byte, 1)); err == nil { t.Fatal("client still open") }
    conn, err := net.DialTimeout("tcp", address, 100*time.Millisecond)
    if err == nil { conn.Close(); t.Fatal("listener still open") }
}

func TestNativeStopWaitsForPreviousRun(t *testing.T) {
    cfproxyEnabled = false
    for i := 0; i < 3; i++ {
        if StartProxy(nil, 0, nil, nil, 0) != 0 { t.Fatal("native start failed") }
        if StopProxy() != 0 { t.Fatal("native stop failed") }
        if globalDone != nil || globalCancel != nil { t.Fatal("native run not joined") }
    }
}


func TestCfTLSNameSeparatesHostAndSNI(t *testing.T) {
    tests := []struct{ host, want string }{
        {"kws2.cakeisalie.co.uk", "cakeisalie.co.uk"},
        {"kws4.pclead.co.uk", "pclead.co.uk"},
        {"kws203.some.example", "some.example"},
        {"kwsX.example.org", "kwsX.example.org"},
        {"kws.example.org", "kws.example.org"},
        {"kws2.web.telegram.org", "web.telegram.org"},
        {"kws2", "kws2"},
    }
    for _, tt := range tests {
        if got := cfTLSName(tt.host); got != tt.want {
            t.Errorf("cfTLSName(%q)=%q, want %q", tt.host, got, tt.want)
        }
    }
    // A direct Telegram WS hostname must remain unchanged by wsConnectOnce.
    if verifiedWsTLSConfig("kws2.web.telegram.org").ServerName != "kws2.web.telegram.org" {
        t.Fatal("Direct WS TLS ServerName modified")
    }
}

func TestWs80RequiresExplicitOptIn(t *testing.T) {
    if cfPlainFallbackEnabled.Load() {
        t.Fatal("Cloudflare plaintext WS cannot be enabled by default")
    }
    SetCfProxyPlainFallback(1)
    if !cfPlainFallbackEnabled.Load() {
        t.Fatal("Explicit opt-in was ignored")
    }
    SetCfProxyPlainFallback(0)
    if cfPlainFallbackEnabled.Load() {
        t.Fatal("Opt-out was ignored")
    }
    if verifiedWsTLSConfig("cakeisalie.co.uk").InsecureSkipVerify {
        t.Fatal("Secure transport must keep hostname and certificate verification")
    }
}


func TestCertificateFailureCannotTriggerPlainFallback(t *testing.T) {
    if isTLSCertificateError(nil) { t.Fatal("nil is not a certificate error") }
    if !isTLSCertificateError(x509.UnknownAuthorityError{}) {
        t.Fatal("unknown CA must prevent downgrade")
    }
    if !isTLSCertificateError(x509.HostnameError{}) {
        t.Fatal("hostname mismatch must prevent downgrade")
    }
}

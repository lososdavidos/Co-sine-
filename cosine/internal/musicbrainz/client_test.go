package musicbrainz

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestACancelledRequestIsNotAnOutage(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("slow") != "" {
			<-r.Context().Done()
			return
		}
		w.Write([]byte("{}"))
	}))
	defer srv.Close()
	c := &Client{HTTP: srv.Client(), BaseURL: srv.URL, UserAgent: "test", Interval: time.Millisecond}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if _, _, err := c.fetch(ctx, srv.URL+"/?slow=1"); errors.Is(err, ErrUnavailable) {
		t.Fatal("a request the caller gave up on counted as an outage")
	}
	if _, _, err := c.fetch(context.Background(), srv.URL+"/"); err != nil {
		t.Fatal("still backing off after a cancelled request:", err)
	}
}
